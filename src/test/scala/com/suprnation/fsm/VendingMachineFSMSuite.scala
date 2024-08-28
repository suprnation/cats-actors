package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ActorSystem
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class VendingMachineFSMSuite extends AsyncFlatSpec with Matchers {
  it should "allow transition to another state (outcome insertMoney)" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          vendingMachine <- actorSystem.replyingActorOf(
            VendingMachine.vendingMachine(
              Item("pizza", 10, 1.00),
              Item("water", 50, 0.50),
              Item("burger", 10, 3.00)
            ),
            "VendingMachine"
          )

          response <- vendingMachine ? SelectProduct("pizza")
          _ <- actorSystem.waitForIdle()
        } yield response
      }
      .unsafeToFuture()
      .map { case messages =>
        messages should contain(RemainingMoney(1.00))
      }
  }

  it should "stay in the same state until the transition pre-requisites have been fulfilled.  " in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          vendingMachine <- actorSystem.replyingActorOf(
            VendingMachine.vendingMachine(
              Item("pizza", 10, 10.00),
              Item("water", 50, 0.50),
              Item("burger", 10, 3.00)
            ),
            "VendingMachine"
          )

          messages <- List(
            vendingMachine ? SelectProduct("pizza"),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? InsertMoney(1.00),
            vendingMachine ? Dispense
          ).flatSequence
          _ <- actorSystem.waitForIdle()
        } yield messages
      }
      .unsafeToFuture()
      .map { case messages =>
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
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          vendingMachine <- actorSystem.replyingActorOf[VendingRequest, List[VendingResponse]](
            VendingMachine.vendingMachine(
              Item("pizza", 0, 10.00)
            ),
            "VendingMachine"
          )

          response <- vendingMachine ? SelectProduct("pizza")
          _ <- actorSystem.waitForIdle()
        } yield response
      }
      .unsafeToFuture()
      .map { case messages =>
        messages should contain(
          // This message has moved to the awaiting payment state
          ProductOutOfStock
        )

      }
  }
}
