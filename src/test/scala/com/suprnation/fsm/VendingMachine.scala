/*
 * Copyright 2024 SuprNation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.suprnation.fsm

import cats.Monoid
import cats.effect.{IO, Ref}
import com.suprnation.actor.ReplyingActor
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.State.StateTimeout
import com.suprnation.actor.fsm.{FSM, StateManager}
import com.suprnation.typelevel.fsm.syntax._

import scala.concurrent.duration._

// Case class to store the data.
case class Item(name: String, amount: Int, price: Double)

// Messages
sealed trait VendingRequest
case class SelectProduct(product: String) extends VendingRequest
case class InsertMoney(amount: Double) extends VendingRequest
case object Dispense extends VendingRequest
case object AwaitingUserTimeout extends VendingRequest

object VendingResponse {
  implicit val vendingResponseMonoid: Monoid[VendingResponse] = new Monoid[VendingResponse] {
    override val empty: VendingResponse = NullVendingResponse
    override def combine(l: VendingResponse, r: VendingResponse): VendingResponse = r
  }
}
sealed trait VendingResponse
case object NullVendingResponse extends VendingResponse
case object ProductOutOfStock extends VendingResponse
case class RemainingMoney(amount: Double) extends VendingResponse
case object Timeout extends VendingResponse
case class Change(product: String, inserted: Double, change: Double) extends VendingResponse
case object PressDispense extends VendingResponse

// States
sealed trait VendingMachineState
case object Idle extends VendingMachineState
case object AwaitingPayment extends VendingMachineState
case object Dispensing extends VendingMachineState
case object OutOfStock extends VendingMachineState

// Data
trait VendingMachineData
case class Uninitialized() extends VendingMachineData
case class ReadyData(product: String, price: Double, inventory: Int) extends VendingMachineData
case class TransactionData(product: String, price: Double, insertedAmount: Double, inventory: Int)
    extends VendingMachineData

object VendingMachine {
  def vendingMachine(
      _inventory: Item*
  ): IO[ReplyingActor[IO, VendingRequest, VendingResponse]] = {
    type SM =
      StateManager[IO, VendingMachineState, VendingMachineData, VendingRequest, VendingResponse]
    type SF = FSM.StateFunction[
      IO,
      VendingMachineState,
      VendingMachineData,
      VendingRequest,
      VendingResponse
    ]
    for {
      inventory <- Ref[IO].of(_inventory.map(x => x.name -> x).toMap)
      productAvailable = (product: String) =>
        inventory.get.map(
          _.get(product).fold(Option.empty[Item])(product =>
            if (product.amount > 0) Option(product) else None
          )
        )

      updateInventory = (product: String, newInventory: Int) =>
        inventory.update(currentInventory =>
          currentInventory.updated(product, currentInventory(product).copy(amount = newInventory))
        )

      idleState: SF = sM => { case Event(SelectProduct(product), Uninitialized()) =>
        productAvailable(product).flatMap {
          case Some(p) =>
            sM.goto(AwaitingPayment)
              .using(ReadyData(product, p.price, p.amount))
              .returning(RemainingMoney(p.price))
          case None =>
            sM.goto(OutOfStock).returning(ProductOutOfStock)
        }
      }

      awaitingPaymentState: SF = sM => {
        // Customer has initiated settling the money
        case Event(InsertMoney(amount), ReadyData(product, price, inventory)) =>
          if (amount >= price) {
            sM.goto(Dispensing)
              .using(TransactionData(product, price, amount, inventory))
              .returning(PressDispense)
          } else {
            // Save the current transaction amount and allow the customer to insert more money
            sM.stay()
              .using(TransactionData(product, price, amount, inventory))
              .returning(RemainingMoney(price - amount))
          }

        // Customer has insert some money prior
        case Event(
              InsertMoney(amount),
              TransactionData(product, price, alreadyInsertedAmount, inventory)
            ) =>
          // Total price has been fulfilled
          if (amount + alreadyInsertedAmount >= price) {
            sM.goto(Dispensing)
              .using(TransactionData(product, price, amount + alreadyInsertedAmount, inventory))
              .returning(PressDispense)
          } else {
            sM.stay()
              .using(TransactionData(product, price, amount + alreadyInsertedAmount, inventory))
              .returning(RemainingMoney(price - (amount + alreadyInsertedAmount)))
          }

        // Customer has timed out, we need to give back the money if the customer inserted some.
        case Event(StateTimeout, _) =>
          sM.goto(Idle).using(Uninitialized()).returning(Timeout)
      }

      dispensingState: SF = sM => {
        case Event(Dispense, TransactionData(product, price, insertedAmount, inventory)) =>
          (updateInventory(product, inventory - 1) >>
            sM.goto(Idle))
            .using(Uninitialized())
            .returning(Change(product, insertedAmount, insertedAmount - price))
      }

      outOfStockState: SF = sM => { case Event(_, _) => // Just an example to handle this state.
        sM.goto(Idle).using(Uninitialized()).returning(ProductOutOfStock)
      }

      // Putting it all together...
      vendingMachine <- FSM[
        IO,
        VendingMachineState,
        VendingMachineData,
        VendingRequest,
        VendingResponse
      ]
        .when(Idle)(idleState)
        .when(AwaitingPayment, stateTimeout = 1.seconds, onTimeout = AwaitingUserTimeout)(
          awaitingPaymentState
        )
        .when(Dispensing)(dispensingState)
        .when(OutOfStock)(outOfStockState)
        //        .withConfig(FSMConfig.withConsoleInformation)
        .startWith(Idle, Uninitialized())
        .initialize
    } yield vendingMachine
  }
}
