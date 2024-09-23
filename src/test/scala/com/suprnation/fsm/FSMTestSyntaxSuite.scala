
package com.suprnation.fsm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.fsm.{FSM, FSMConfig, Normal}
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.fsm.FSMTestSyntaxSuite.{FsmIdle, FsmRunning, FsmState}
import com.suprnation.typelevel.fsm.test.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object FSMTestSyntaxSuite {

  private[FSMTestSyntaxSuite] sealed trait FsmState
  private[FSMTestSyntaxSuite] case object FsmIdle extends FsmState
  private[FSMTestSyntaxSuite] case object FsmRunning extends FsmState
  private[FSMTestSyntaxSuite] case object FsmStopped extends FsmState

  private[FSMTestSyntaxSuite] sealed trait FsmRequest

  def actor(startWith: FsmState): IO[ReplyingActor[IO, FsmRequest, List[Any]]] =
    FSM[IO, FsmState, Int, FsmRequest, List[Any]]
      .when(FsmIdle)(sM => { case _ => sM.stop(Normal, 0) })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(startWith, 1)
      .initialize

}

class FSMTestSyntaxSuite extends AsyncFlatSpec with Matchers {

  it should "set and retrieve the current state" in {
    ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).use { actorSystem => 
    (for {
      actor <- FSMTestSyntaxSuite.actor(startWith = FsmIdle)
      _ <- actorSystem.replyingActorOf(actor)

      fsmTestKit <- actor.fsmTestKit[FsmState, Int]
      _ <- fsmTestKit.setState(FsmRunning, 4)
      state <- fsmTestKit.currentState
    } yield state)}.unsafeToFuture().map { case (stateName, stateData) =>
      stateName should be(FsmRunning)
      stateData should be(4)
    }

  }

}
