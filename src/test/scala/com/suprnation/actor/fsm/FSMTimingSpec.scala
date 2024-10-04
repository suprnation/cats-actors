package com.suprnation.actor.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.effect.std.CountDownLatch
import cats.implicits._
import cats.effect.implicits._
import com.suprnation.actor.{InternalActorRef, ReplyingActor, ReplyingActorRef}
import com.suprnation.actor.SupervisorStrategy.{Decider, defaultDecider, Resume}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.sender.Sender.BaseActor.{Ask, BaseActorMessages, Forward, Tell}
import com.suprnation.actor.test.TestKit
import com.suprnation.typelevel.actors.syntax._
import com.suprnation.typelevel.fsm.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException


object FSMTimingSpec extends TestKit {

  def awaitMessage(
    sM: StateManager[IO, TState, Int, Message, List[Message]],
    max: FiniteDuration
  ): IO[Unit] =
    awaitCond(
      sM.actorContext.self
        .asInstanceOf[InternalActorRef[IO, Message, List[Message]]]
        .actorCellRef.get
        .flatMap(
          _.map(_.dispatchContext.mailbox.hasMessage)
            .getOrElse(IO.raiseError(new Exception("No actor cell found!")))
        ), 
      max
    )

  def suspend[I, O](actorRef: ReplyingActorRef[IO, I, O]): IO[Unit] = actorRef match {
    case l: InternalActorRef[IO, I, O] => l.suspend(None)
    case _                   => IO.unit
  }

  def resume[I, O](actorRef: ReplyingActorRef[IO, I, O]): IO[Unit] = actorRef match {
    case l: InternalActorRef[IO, I, O] => l.resume(None)
    case _                   => IO.unit
  }



  sealed trait Message

  sealed trait TState extends Message
  case object Initial extends TState
  case object TestStateTimeout extends TState
  case object TestStateTimeoutOverride extends TState
  case object TestSingleTimer extends TState
  case object TestSingleTimerResubmit extends TState
  case object TestRepeatedTimer extends TState
  case object TestUnhandled extends TState
  case object TestCancelTimer extends TState
  case object TestCancelStateTimerInNamedTimerMessage extends TState
  case object TestCancelStateTimerInNamedTimerMessage2 extends TState
  case object TestStoppingActorStateTimeout extends TState

  case object Tick extends Message
  case object Tock extends Message
  case object Cancel extends Message
  case object SetHandler extends Message
  case object StateTimeout extends Message
  case class Transition(from: TState, to: TState) extends Message

  case class Send(message: Message, actor: ReplyingActorRef[IO, Message, List[Message]])

  final case class Unhandled(msg: AnyRef)

  def stateMachine(tester: ActorRef[IO, Any]): IO[ReplyingActor[IO, Message, List[Message]]] =
    when[IO, TState, Int, Message, List[Message]](Initial)( sM => {
      case Event(TestSingleTimer, _) =>
        sM.startSingleTimer("tester", Tick, 500.millis) >>
        sM.goto(TestSingleTimer)
      case Event(TestRepeatedTimer, _) =>
        sM.startTimerWithFixedDelay("tester", Tick, 100.millis) >>
        sM.goto(TestRepeatedTimer).using(4)
      case Event(TestStateTimeoutOverride, _) =>
        sM.goto(TestStateTimeout)
      case Event(x: TState, _) => sM.goto(x)
    })
      .when(TestStateTimeout, 800.millis, StateTimeout)( sM => {
        case Event(StateTimeout, _) => sM.goto(Initial)
        case Event(Cancel, _)       => sM.goto(Initial).replying(List(Cancel))
      })
      .when(TestSingleTimer)( sM => {
        case Event(Tick, _) =>
          (tester ! Tick) >>
          sM.goto(Initial)
      })
      .onTransition {
        case Initial -> TestSingleTimerResubmit => _.startSingleTimer("blah", Tick, 500.millis)
      }
      .onTransition { 
        case f -> t if f != Initial => _ => tester ! Transition(f, t)
      }
      .when(TestSingleTimerResubmit)( sM => {
        case Event(Tick, _) =>
          (tester ! Tick) >>
          sM.startSingleTimer("blah", Tock, 500.millis) >>
          sM.stay()
        case Event(Tock, _) =>
          (tester ! Tock) >>
          sM.goto(Initial)
      })
      .when(TestCancelTimer)( sM => {
        case Event(Tick, _) =>
          sM.startSingleTimer("hallo", Tock, 1.milli) >>
          awaitMessage(sM, 1.second) >>
          sM.cancelTimer("hallo") >>
          (tester ! Tick) >> // WHO IS THE SENDER HERE???!?
          sM.startSingleTimer("hallo", Tock, 500.millis) >>
          sM.stay()
        case Event(Tock, _) =>
          (tester ! Tock) >>
          sM.stay()
        case Event(Cancel, _) =>
          sM.cancelTimer("hallo") >>
          sM.goto(Initial)
      })
      .when(TestRepeatedTimer)( sM => {
        case Event(Tick, remaining) =>
          (tester ! Tick) >>
          (if (remaining == 0) {
            sM.cancelTimer("tester") >>
            sM.goto(Initial)
          } else {
            sM.stay().using(remaining - 1)
          })
      })
      .when(TestCancelStateTimerInNamedTimerMessage)( sM => {
        // FSM is suspended after processing this message and resumed 500ms later
        case Event(Tick, _) =>
          suspend(sM.actorContext.self) >>
          sM.startSingleTimer("named", Tock, 1.millis) >>
          awaitMessage(sM, 1.second) >>
          sM.stay().forMax(1.millis, StateTimeout).replying(List(Tick))
        case Event(Tock, _) =>
          sM.goto(TestCancelStateTimerInNamedTimerMessage2)
      })
      .when(TestCancelStateTimerInNamedTimerMessage2)( sM => {
        case Event(StateTimeout, _) =>
          sM.goto(Initial)
        case Event(Cancel, _) =>
          sM.goto(Initial).replying(List(Cancel))
      })
      //.withConfig(FSMConfig.withConsoleInformation[IO, TState, Int, Message, List[Message]])
      .startWith(Initial, 0)

      .initialize


  def stoppingActor: IO[ReplyingActor[IO, Message, List[Message]]] =
    when[IO, TState, Int, Message, List[Message]](Initial, 200.millis, StateTimeout)( sM => {
      case Event(TestStoppingActorStateTimeout, _) =>
        sM.actorContext.stop(sM.actorContext.self) >>
        sM.stay()
    })
      .startWith(Initial, 0)
      .initialize

  class ProxyActor(tracker: ActorRef[IO, Any]) extends Actor[IO, Any] {
    override def receive: Receive[IO, Any] = {
      case Send(m, a) => a ! m
      case List() => IO.unit
      case m => tracker ! m
    }
  }
}




class FSMTimingSpec extends AsyncFlatSpec with Matchers {
  import FSMTimingSpec._

 "A Finite State Machine" should "receive StateTimeout" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          fsm <- system.replyingActorOf(stateMachine(testActor))

          _ <- within(2.seconds) {
            within(500.millis, 2.seconds) {
              (fsm ! TestStateTimeout) >>
              (expectMsgs(testActor, 1.second)(Transition(TestStateTimeout, Initial)))
            }
          }

          _ <- expectNoMsg(testActor, 2.seconds)
        } yield (succeed)
      }.unsafeToFuture()
 }

  it should "cancel a StateTimeout" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- within(1.second) {
            (proxyActor ! Send(TestStateTimeout, fsm)) >>
            (proxyActor ! Send(Cancel, fsm)) >>
            (expectMsgs(testActor, 1.second)(List(Cancel), Transition(TestStateTimeout, Initial)))
          }
          _ <- expectNoMsg(testActor, 200.millis)

        } yield (succeed)
      }.unsafeToFuture()
  }

  it should "allow StateTimeout override" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          // the timeout in state TestStateTimeout is 800 ms, then it will change to Initial
          _ <- proxyActor ! Send(TestStateTimeoutOverride, fsm)
          _ <- expectNoMsg(testActor, 400.millis)

          _ <- within(1.second) {
            (proxyActor ! Send(Cancel, fsm)) >>
            (expectMsgs(testActor, 1.second)(List(Cancel), Transition(TestStateTimeout, Initial)))
          }
          _ <- expectNoMsg(testActor, 200.millis)

        } yield (succeed)
      }.unsafeToFuture()
  }

  it should "receive single-shot timer" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- within(500.millis, 1.second) {
            (proxyActor ! Send(TestSingleTimer, fsm)) >>
            (expectMsgs(testActor, 1.second)(Tick, Transition(TestSingleTimer, Initial)))
          }
          _ <- expectNoMsg(testActor, 2.seconds)

        } yield (succeed)
      }.unsafeToFuture()
  }

  it should "resubmit single-shot timer" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- within(500.millis, 1.second) {
            (proxyActor ! Send(TestSingleTimerResubmit, fsm)) >>
            (expectMsgs(testActor, 1.second)(Tick))
          }

          _ <- within(1.second) {
            expectMsgs(testActor, 1.second)(Tock, Transition(TestSingleTimerResubmit, Initial))
          }
          _ <- expectNoMsg(testActor, 1.second)

        } yield (succeed)
      }.unsafeToFuture()
  }


  it should "correctly cancel a named timer" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- fsm ! TestCancelTimer

          _ <- within(500.millis) {
            (proxyActor ! Send(Tick, fsm)) >>
            (expectMsgs(testActor, 1.second)(Tick))
          }

          _ <- within(300.millis, 1.second) {
            expectMsgs(testActor, 1.second)(Tock)
          }

          _ <- fsm ! Cancel
          _ <- expectMsgs(testActor, 1.second)(Transition(TestCancelTimer, Initial))
        } yield (succeed)
      }.unsafeToFuture()
  }

  it should "not get confused between named and state timers" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- fsm ! TestCancelStateTimerInNamedTimerMessage
          _ <- proxyActor ! Send(Tick, fsm)
          _ <- expectMsgs(testActor, 500.millis)(List(Tick))
          _ <- IO.sleep(200.millis) // this is ugly: need to wait for StateTimeout to be queued
          _ <- resume(fsm)
          _ <- expectMsgs(testActor, 500.millis)(Transition(TestCancelStateTimerInNamedTimerMessage, TestCancelStateTimerInNamedTimerMessage2))
          _ <- proxyActor ! Send(Cancel, fsm)

          _ <- expectMsgs(testActor, 500.millis)(List(Cancel), Transition(TestCancelStateTimerInNamedTimerMessage2, Initial))
        } yield (succeed)
      }.unsafeToFuture()
  }

  it should "receive and cancel a repeated timer" in {
    ActorSystem[IO]("FSM Actor")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Any]("FSM timing test ignoring actor")
              .track("FSM timing test ignoring actor")(cache)
          )
          proxyActor <- system.actorOf(new ProxyActor(testActor))
          fsm <- system.replyingActorOf(stateMachine(proxyActor))

          _ <- fsm ! TestRepeatedTimer
          _ <- IO.sleep(2500.millis)
          messages <- testActor.messageBuffer 
        } yield (messages._2)
      }.unsafeToFuture()
      .map {
        case messages => messages should equal (List(Tick, Tick, Tick, Tick, Tick, Transition(TestRepeatedTimer, Initial)))
      }
  }
}
