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

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSM, FSMConfig, Normal}
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.actor.fsm.TerminationFSMSuite._
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
  ): IO[ReplyingActor[IO, FsmRequest, List[Any]]] =
    FSM[IO, FsmState, Int, FsmRequest, List[Any]]
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
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          stoppedDef <- Deferred[IO, Int]
          actor <- actorSystem.replyingActorOf(
            TerminationFSMSuite.actor(
              startWith = FsmIdle,
              stoppedDef
            )
          )

          _ <- actor ! FsmStop

          fsmExitCode <- IO.race(IO.pure(-1).delayBy(4.seconds), stoppedDef.get)
          _ <- actorSystem.waitForIdle()
        } yield fsmExitCode
      }
      .unsafeToFuture()
      .map { fsmExitCode =>
        fsmExitCode should be(Right(0))
      }
  }

}
