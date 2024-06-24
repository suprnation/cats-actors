package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.fsm.FSM
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax.Timeout
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

sealed trait TimeoutState
case object NoTimeout extends TimeoutState
case object DefaultTimeout extends TimeoutState

sealed trait TimeoutRequest
case class GotoNoTimeoutState(forceTimeout: Timeout[TimeoutRequest] = None) extends TimeoutRequest
case class GotoTimeoutState(forceTimeout: Timeout[TimeoutRequest] = None) extends TimeoutRequest

object TimeoutActor {
  def timeoutActor(startWith: TimeoutState): IO[Actor[IO, TimeoutRequest]] =
    FSM[IO, TimeoutState, Int, TimeoutRequest, Any]
      .when(NoTimeout) {
        case (Event(GotoNoTimeoutState(None), _), sM) => sM.stay()
        case (Event(GotoNoTimeoutState(fd), _), sM)   => sM.forMax(fd)
        case (Event(GotoTimeoutState(_), data), sM)   => sM.stayAndReply(data)
      }
      //      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(startWith, 0)
      .initialize

}

class TimeoutFSMSuite extends AsyncFlatSpec with Matchers {
  it should "should not timeout if timeout not set" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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

  it should "timeout based on default timeout state" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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

  it should "should timeout if an override timeout is set (when initial was not set)" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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

  it should "should timeout if an override timeout is set (when state has default)" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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

  it should "cancel any timeout from the current state once we move to another state" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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

  it should "cancel any timeouts set once we move to another state" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      peanoNumber <- actorSystem.replyingActorOf[PeanoNumber, Int](PeanoNumbers.peanoNumbers)

      peanoNumberActor <- actorSystem.actorOf[PeanoNumber](
        AbsorbReplyActor(peanoNumber, buffer),
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
