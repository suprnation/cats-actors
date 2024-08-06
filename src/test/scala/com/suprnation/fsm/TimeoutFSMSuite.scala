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

sealed trait TimeoutState
case object Awake extends TimeoutState
case object Asleep extends TimeoutState

sealed trait TimeoutRequest
case class Sleep(timedOut: Boolean) extends TimeoutRequest
case class WakeUp(stayAwakeFor: Option[FiniteDuration] = None) extends TimeoutRequest


object TimeoutActor {

  def forMaxTimeoutActor(startWith: TimeoutState, timedOutDef: Deferred[IO, Boolean]): IO[Actor[IO, TimeoutRequest]] =
    FSM[IO, TimeoutState, Deferred[IO, Boolean], TimeoutRequest, Any]
      .when(Awake) {
        case (Event(Sleep(timedOut), d), sM) if timedOut =>
          d.complete(true) *>
            sM.goto(Asleep).replying(Asleep)

        case (Event(_, _), sM) => sM.stayAndReply(Awake)
      }
      .when(Asleep) {
        case (Event(WakeUp(stayAwakeFor), _), sM) =>
          sM.goto(Awake)
            .forMax(stayAwakeFor.map((_, Sleep(timedOut = true))))
            .replying(Awake)
      }
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(startWith, timedOutDef)
      .initialize

  def stateTimeoutActor(startWith: TimeoutState, stayAwakeFor: FiniteDuration, timedOutDef: Deferred[IO, Boolean]): IO[Actor[IO, TimeoutRequest]] =
    FSM[IO, TimeoutState, Deferred[IO, Boolean], TimeoutRequest, Any]
      .when(Awake, stayAwakeFor, Sleep(timedOut = true)) {
        case (Event(Sleep(timedOut), d), sM) if timedOut =>
          d.complete(true) *>
            sM.goto(Asleep).replying(Asleep)

        case (Event(_, _), sM) => sM.stayAndReply(Awake)
      }
      .when(Asleep) {
        case (Event(WakeUp(_), _), sM) =>
          sM.goto(Awake).replying(Awake)
      }
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(startWith, timedOutDef)
      .initialize

}

class TimeoutFSMSuite extends AsyncFlatSpec with Matchers {

  it should "timeout the Awake state using the 'forMax' and go back to sleep" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timedOut <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(TimeoutActor.forMaxTimeoutActor(Asleep, timedOut))

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp(stayAwakeFor = 2.seconds.some)

      _ <- IO.race(IO.sleep(5.seconds), timedOut.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(Awake, Asleep))
    }
  }

  it should "timeout the Awake state using the 'when' timeout and go back to sleep" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])

      timedOut <- Deferred[IO, Boolean]
      timeoutActor <- actorSystem.actorOf(TimeoutActor.stateTimeoutActor(Asleep, 2.seconds, timedOut))

      actor <- actorSystem.actorOf[TimeoutRequest](
        AbsorbReplyActor(timeoutActor, buffer),
        "actor"
      )
      _ <- actor ! WakeUp()

      _ <- IO.race(IO.sleep(5.seconds), timedOut.get)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(Awake, Asleep))
    }
  }

}
