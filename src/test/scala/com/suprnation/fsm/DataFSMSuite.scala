package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.StateManager
import com.suprnation.actor.props.{Props, PropsF}
import com.suprnation.actor.{Actor, ActorSystem}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

sealed trait PeanoState
case object Forever extends PeanoState

sealed trait PeanoNumber
case object Zero extends PeanoNumber
case object Succ extends PeanoNumber
case object CurrentState

object PeanoNumbers {
  val peanoNumbers: IO[Actor[IO]] =
    when[IO, PeanoState, Int](Forever) {
      case (Event(Zero, data), sM: StateManager[IO, PeanoState, Int]) =>
        sM.stayAndReply(data)

      case (Event(Succ, data), sM: StateManager[IO, PeanoState, Int]) =>
        sM.stay().using(data + 1).replying(data + 1)

      case (Event(CurrentState, data), sM: StateManager[IO, PeanoState, Int]) =>
        sM.stayAndReply(data)
    }
//      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Forever, 0)
      .initialize
}

class DataFSMSuite extends AsyncFlatSpec with Matchers {
  it should "update state data" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.actorOf(PropsF[IO](PeanoNumbers.peanoNumbers))

      peanoNumberActor <- actorSystem.actorOf(
        Props[IO](AbsorbReplyActor(peanoNumber, buffer)),
        "peano-number-absorb-actor"
      )
      _ <- peanoNumberActor ! Zero
      _ <- peanoNumberActor ! Succ
      _ <- peanoNumberActor ! Succ
      _ <- peanoNumberActor ! Succ
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(List(0, 1, 2, 3))
    }
  }

  it should "update state data for very large states" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.actorOf(PropsF[IO](PeanoNumbers.peanoNumbers))

      peanoNumberActor <- actorSystem.actorOf(
        Props[IO](AbsorbReplyActor(peanoNumber, buffer)),
        "peano-number-absorb-actor"
      )
      _ <- peanoNumberActor ! Zero
      _ <- (peanoNumberActor ! Succ).replicateA(10000)
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(Range.inclusive(0, 10000).toList)
    }
  }
}
