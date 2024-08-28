package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSM, FSMConfig, Normal}
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.fsm.TerminationFSMSuite._
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

object TerminationFSMSuite {

  private[TerminationFSMSuite] sealed trait FsmState
  private[TerminationFSMSuite] case object FsmIdle extends FsmState

  private[TerminationFSMSuite] sealed trait FsmRequest
  private[TerminationFSMSuite] case object FsmStop extends FsmRequest

  def actor(
      startWith: FsmState,
      stopped: Deferred[IO, Int]
  ): IO[ReplyingActor[IO, FsmRequest, Any]] =
    FSM[IO, FsmState, Int, FsmRequest, Any]
      .when(FsmIdle)(sM => { case Event(FsmStop, _) =>
        sM.stop(Normal, 0)
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .onTermination {
        case (Normal, stateData) =>
          stopped.complete(stateData).void
        case _ => IO.unit
      }
      .startWith(startWith, 1)
      .initialize

}

class TerminationFSMSuite extends AsyncFlatSpec with Matchers {

  it should "create child actor and send a message to self" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)

      stoppedDef <- Deferred[IO, Int]
      actor <- actorSystem.actorOf(
        TerminationFSMSuite.actor(
          startWith = FsmIdle,
          stoppedDef
        )
      )

      _ <- actor ! FsmStop

      fsmExitCode <- IO.race(IO.pure(-1).delayBy(4.seconds), stoppedDef.get)
      _ <- actorSystem.waitForIdle()
    } yield fsmExitCode).unsafeToFuture().map { fsmExitCode =>
      fsmExitCode should be(Right(0))
    }
  }

}
