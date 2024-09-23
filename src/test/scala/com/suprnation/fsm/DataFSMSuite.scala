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
    when[IO, PeanoState, Int, PeanoNumber, List[Int]](Forever)(sM => {
      case Event(Zero, data) =>
        sM.stayAndReturn(List(data))

      case Event(Succ, data) =>
        sM.stay().using(data + 1).returning(List(data + 1))

      case Event(CurrentState, data) =>
        sM.stayAndReturn(List(data))
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
