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

package com.suprnation.actor.fsm
package broadcast

import cats.implicits._
import cats.effect.{IO, Ref}
import cats.effect.implicits._
import com.suprnation.actor.{ActorSystem, AllForOneStrategy, ReplyingActor, ReplyingActorRef}
import com.suprnation.actor.broadcast.TreenodeActor._
import com.suprnation.actor.fsm.FSM.Event

import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.SupervisorStrategy.Escalate
import com.suprnation.typelevel.actors.syntax.BroadcastSyntax._
import com.suprnation.typelevel.fsm.syntax._

import scala.concurrent.duration.FiniteDuration
import scala.collection.immutable.Queue

object TreenodeFsmActor {

  trait TreenodeState
  case object InProgress extends TreenodeState

  case class TreenodeData(requests: Queue[String]) {

    def andAlso(additionalRequests: Queue[String]): TreenodeData =
      TreenodeData(requests ++ additionalRequests)

  }

  private def createConfig(
      name: String,
      dataRef: Option[Ref[IO, TreenodeData]]
  ): FSMConfig[IO, TreenodeState, TreenodeData, Ping.type, Response] =
    FSMConfig(
      debug = true,
      receive = (
          _: Any,
          _: Option[NoSendActorRef[IO]],
          _: State[TreenodeState, TreenodeData, Ping.type, Response]
      ) => IO.unit,
      transition = (
          _: State[TreenodeState, TreenodeData, Ping.type, Response],
          target: State[TreenodeState, TreenodeData, Ping.type, Response]
      ) => dataRef.map(_.set(target.stateData)).getOrElse(IO.unit),
      startTimer = (_: String, _: Ping.type, _: FiniteDuration, _: Boolean) => IO.unit,
      cancelTimer = (_: String) => IO.unit
    )

  def create(
      system: ActorSystem[IO],
      name: String,
      creationDelay: FiniteDuration,
      delay: FiniteDuration,
      dataRef: Option[Ref[IO, TreenodeData]],
      testMode: TestMode = Normal
  )(
      children: ReplyingActorRef[IO, Ping.type, Response]*
  ): IO[ReplyingActorRef[IO, Ping.type, Response]] =
    when[IO, TreenodeState, TreenodeData, Ping.type, Response](InProgress) { sM =>
      { case Event(Ping, startingData) =>
        if (children.isEmpty) testMode match {
          case Normal => IO.sleep(delay) *> sM.stayAndReturn(Pong(Queue(name)))
          case Error  => sM.stop(Failure("Test Failure")).returning(Fail(Error))
          case Wrong  => sM.stayAndReturn(Peng)
        }
        else
          (children ? Ping)
            .mapping { case Fail(error) => IO.raiseError(error) }
            .expecting[Pong]
            .parallel
            .map(_.combineAll) >>= { pong =>
            sM.stayAndReturn(pong.append(name))
              .using(startingData.andAlso(pong.processedBy))
          }
      }
    }.withPreStart(c => IO.sleep(creationDelay) >> c.stay())
      .withConfig(createConfig(name, dataRef))
      .withSupervisorStrategy(AllForOneStrategy[IO]() { case _: Throwable => Escalate })
      .startWith(InProgress, TreenodeData(Queue()))
      .initialize >>= (system.replyingActorOf[Ping.type, Response](_))

}
