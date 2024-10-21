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

package com.suprnation.actor.test

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.{Actor, ActorSystem}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class TestKitSpec extends AsyncFlatSpec with Matchers with TestKit {

  "expectTerminated" should "assert true when the actor is terminated" in {
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          actorRef <- actorSystem.actorOf(Actor.empty[IO, Any])

          _ <- actorRef.stop
          _ <- actorSystem.waitForIdle()

          _ <- expectTerminated(actorRef)
        } yield succeed
      }
      .unsafeToFuture()
  }

  "expectTerminated" should "throw exception when the actor is alive" in {
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          actorRef <- actorSystem.actorOf(Actor.empty[IO, Any])
          isTerminated <- expectTerminated(actorRef).as(true).handleError(_ => false)
        } yield isTerminated should be(false)
      }
      .unsafeToFuture()
  }

  "expectMsgPF" should "succeed when actor received expected message defined in partial function" in {
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          actorRef <- actorSystem.actorOf(
            TrackingActor.create[IO, Any, Any](
              Actor.withReceive[IO, Any] { case _ =>
                IO.unit
              }
            )
          )

          _ <- actorRef ! "Hello"

          _ <- actorSystem.waitForIdle()
          _ <- expectMsgPF(actorRef, 1 second) {
            case s: String if s == "Hello" => ()
          }
        } yield succeed
      }
      .unsafeToFuture()
  }

  "expectMsgPF" should "fail when actor did not receive expected message defined in partial function" in {
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          actorRef <- actorSystem.actorOf(
            TrackingActor.create[IO, Any, Any](
              Actor.withReceive[IO, Any] { case _ =>
                IO.unit
              }
            )
          )

          _ <- actorRef ! "Bye"

          receivedMessage <- expectMsgPF(actorRef, 1 second) {
            case s: String if s == "Hello" => ()
          }.as(true).handleError(_ => false)
        } yield receivedMessage should be(false)
      }
      .unsafeToFuture()
  }

}
