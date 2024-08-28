package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.StateManager
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

sealed trait PeanoState
case object Forever extends PeanoState

sealed trait PeanoNumber
case object Zero extends PeanoNumber
case object Succ extends PeanoNumber
case object CurrentState extends PeanoNumber

object PeanoNumbers {
  val peanoNumbers: IO[ReplyingActor[IO, PeanoNumber, List[Int]]] =
    when[IO, PeanoState, Int, PeanoNumber, Int](Forever)(sM => {
      case Event(Zero, data) =>
        sM.stayAndReply(data)

      case Event(Succ, data) =>
        sM.stay().using(data + 1).replying(data + 1)

      case Event(CurrentState, data) =>
        sM.stayAndReply(data)
    })
      //      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Forever, 0)
      .initialize
}

class DataFSMSuite extends AsyncFlatSpec with Matchers {
  it should "update state data" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          peanoNumberActor <- actorSystem.replyingActorOf(PeanoNumbers.peanoNumbers)

          responses <- List(
            peanoNumberActor ? Zero,
            peanoNumberActor ? Succ,
            peanoNumberActor ? Succ,
            peanoNumberActor ? Succ
          ).flatSequence
          _ <- actorSystem.waitForIdle()
        } yield responses
      }
      .unsafeToFuture()
      .map { messages =>
        messages should be(List(0, 1, 2, 3))
      }
  }

  it should "update state data for very large states" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          peanoNumberActor <- actorSystem.replyingActorOf(PeanoNumbers.peanoNumbers)

          r0 <- peanoNumberActor ? Zero
          r1 <- (peanoNumberActor ? Succ).replicateA(10000)
          _ <- actorSystem.waitForIdle()
        } yield r0 ++ r1.flatten
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(Range.inclusive(0, 10000).toList)
      }
  }
}
