package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ActorSystem
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

/** This actor will watch all replies from the underlying actor and save the replies.
  */
case class AbsorbReplyActor[Msg](
    forwardTo: ActorRef[IO, Msg],
    replyBuffer: Ref[IO, Vector[Any]]
) extends Actor[IO, Msg] {
  override def receive: Receive[IO, Msg] = {
    // The actor has replied to us - let's record this.
    case message if sender.isDefined && sender.get == forwardTo =>
      replyBuffer.update(_ ++ Vector(message))
    // We will forward to the actor
    case message => forwardTo ! message
  }
}

class VendingMachineFSMSuite extends AsyncFlatSpec with Matchers {
  it should "allow transition to another state (outcome insertMoney)" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor").allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.replyingActorOf(
        VendingMachine.vendingMachine(
          Item("pizza", 10, 1.00),
          Item("water", 50, 0.50),
          Item("burger", 10, 3.00)
        ),
        "VendingMachine"
      )

      vendingMachineProxy <- actorSystem.actorOf[VendingRequest](
        AbsorbReplyActor(vendingMachine, buffer),
        "absorb-actor"
      )
      _ <- vendingMachineProxy ! SelectProduct("pizza")
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { case messages =>
      messages should contain(RemainingMoney(1.00))
    }
  }

  it should "stay in the same state until the transition pre-requisites have been fulfilled.  " in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor").allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf(
        VendingMachine.vendingMachine(
          Item("pizza", 10, 10.00),
          Item("water", 50, 0.50),
          Item("burger", 10, 3.00)
        ),
        "VendingMachine"
      )

      vendingMachineProxy <- actorSystem.actorOf[VendingRequest](
        AbsorbReplyActor(vendingMachine, buffer),
        "absorb-actor"
      )
      _ <- vendingMachineProxy ! SelectProduct("pizza")
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! InsertMoney(1.00)
      _ <- vendingMachineProxy ! Dispense
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { case messages =>
      messages.toList should be(
        List(
          // This message has moved to the awaiting payment state
          RemainingMoney(10.00),
          // Here we remain in the payment state for a while (until the payment prerequisite has been fulfilled)
          RemainingMoney(9.00),
          RemainingMoney(8.00),
          RemainingMoney(7.00),
          RemainingMoney(6.00),
          RemainingMoney(5.00),
          RemainingMoney(4.00),
          RemainingMoney(3.00),
          RemainingMoney(2.00),
          RemainingMoney(1.00),
          // here we move to the dispense state.
          PressDispense,
          // now we get the change
          Change("pizza", 10, 0)
        )
      )

    }
  }

  it should "allow transition to another state (outcome outOfStock)" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor").allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf[VendingRequest](
        VendingMachine.vendingMachine(
          Item("pizza", 0, 10.00)
        ),
        "VendingMachine"
      )

      vendingMachineProxy <- actorSystem.actorOf[VendingRequest](
        AbsorbReplyActor(vendingMachine, buffer),
        "absorb-actor"
      )
      _ <- vendingMachineProxy ! SelectProduct("pizza")
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { case messages =>
      messages should contain(
        // This message has moved to the awaiting payment state
        ProductOutOfStock
      )

    }
  }
}
