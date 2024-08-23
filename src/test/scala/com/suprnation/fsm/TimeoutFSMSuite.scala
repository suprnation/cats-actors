package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.implicits.catsSyntaxOptionId
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSM, FSMConfig}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax.FSMStateSyntaxOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{DurationInt, FiniteDuration}

sealed trait Replies
case object WakingUp extends Replies
case object GotNudged extends Replies
case object StateTimeoutSleep extends Replies
case object TransitionTimeoutSleep extends Replies

sealed trait TimeoutState
case object Awake extends TimeoutState
case object Nudged extends TimeoutState
case object Asleep extends TimeoutState

sealed trait TimeoutRequest
case class StateSleep() extends TimeoutRequest
case class TransitionSleep() extends TimeoutRequest
case class Nudge() extends TimeoutRequest
case class WakeUp(stayAwakeFor: Option[FiniteDuration] = None) extends TimeoutRequest

object TimeoutActor {

  def forMaxTimeoutActor(
      startWith: TimeoutState,
      defaultStateStayAwakeFor: FiniteDuration,
      timeOutDef: Deferred[IO, Boolean]
  ): IO[Actor[IO, TimeoutRequest]] =
    FSM[IO, TimeoutState, Int, TimeoutRequest, Any]
      .when(Awake, defaultStateStayAwakeFor, StateSleep())(sM => {
        case Event(StateSleep(), _) =>
          timeOutDef.complete(true) *>
            sM.goto(Asleep).replying(StateTimeoutSleep)

        case Event(TransitionSleep(), _) =>
          timeOutDef.complete(true) *>
            sM.goto(Asleep).replying(TransitionTimeoutSleep)

        case Event(Nudge(), _) => sM.goto(Nudged).replying(GotNudged)
      })
      .when(Nudged)(sM => _ => sM.stayAndReply(GotNudged))
      .when(Asleep)(sM => {
        case Event(WakeUp(stayAwakeFor), _) =>
          sM.goto(Awake)
            .forMax(stayAwakeFor.map((_, TransitionSleep())))
            .replying(WakingUp)

        case Event(Nudge(), _) => sM.goto(Nudged).replying(GotNudged)
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(startWith, 0)
      .initialize

}

class TimeoutFSMSuite extends AsyncFlatSpec with Matchers {

  it should "timeout the Awake state using the 'forMax' and go back to sleep" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timeOutDef <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(
        TimeoutActor.forMaxTimeoutActor(
          Asleep,
          3.seconds,
          timeOutDef
        )
      )

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp(stayAwakeFor = 2.seconds.some)

      _ <- IO.race(IO.sleep(5.seconds), timeOutDef.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(WakingUp, TransitionTimeoutSleep))
    }
  }

  it should "timeout the Awake state using the 'when' timeout and go back to sleep" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timeOutDef <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(
        TimeoutActor.forMaxTimeoutActor(
          Asleep,
          3.seconds,
          timeOutDef
        )
      )

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp()

      _ <- IO.race(IO.sleep(5.seconds), timeOutDef.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(WakingUp, StateTimeoutSleep))
    }
  }

  it should "override state default timeout with the 'forMax' one" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timeOutDef <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(
        TimeoutActor.forMaxTimeoutActor(
          Asleep,
          2.seconds,
          timeOutDef
        )
      )

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp(stayAwakeFor = 3.seconds.some)

      _ <- IO.race(IO.sleep(5.seconds), timeOutDef.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(WakingUp, TransitionTimeoutSleep))
    }
  }

  it should "not timeout once we move to another state" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timeOutDef <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(
        TimeoutActor.forMaxTimeoutActor(
          Asleep,
          2.seconds,
          timeOutDef
        )
      )

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp(stayAwakeFor = 2.seconds.some)
      _ <- actor ! Nudge()

      // IO.sleep should win here as the actor's timeout should be cancelled
      _ <- IO.race(IO.sleep(4.seconds), timeOutDef.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(WakingUp, GotNudged))
    }
  }

}
