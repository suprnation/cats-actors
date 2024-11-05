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
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.{Actor, ActorSystem}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.Assertion
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class TestKitSpec extends AsyncFlatSpec with Matchers with TestKit {

  type Fixture = (ActorSystem[IO], ActorRef[IO, Any])

  protected def testActorSystem(test: Fixture => IO[Assertion]): Future[Assertion] =
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

          result <- test(actorSystem, actorRef)
        } yield result
      }
      .unsafeToFuture()

  protected def testActorSystem(
      actor: Actor[IO, Any]
  )(test: Fixture => IO[Assertion]): Future[Assertion] =
    ActorSystem[IO]()
      .use { actorSystem =>
        for {
          actorRef <- actorSystem.actorOf(TrackingActor.create[IO, Any, Any](actor))

          result <- test(actorSystem, actorRef)
        } yield result
      }
      .unsafeToFuture()

  "expectTerminated" should "assert true when the actor is terminated" in {
    testActorSystem { case (actorSystem, actorRef) =>
      for {
        _ <- actorRef.stop
        _ <- actorSystem.waitForIdle()

        _ <- expectTerminated(actorRef)
      } yield succeed
    }
  }

  "expectTerminated" should "throw exception when the actor is alive" in {
    testActorSystem { case (_, actorRef) =>
      expectTerminated(actorRef)
        .as(true)
        .handleError(_ => false)
        .map(_ should be(false))
    }
  }

  "expectMsgPF" should "succeed when actor received expected message defined in partial function" in {
    testActorSystem { case (_, actorRef) =>
      for {
        _ <- actorRef ! "Hello"

        _ <- expectMsgPF(actorRef, 1 second) {
          case s: String if s == "Hello" => ()
        }
      } yield succeed
    }
  }

  "expectMsgPF" should "fail when actor did not receive expected message defined in partial function" in {
    testActorSystem { case (_, actorRef) =>
      for {
        _ <- actorRef ! "Bye"

        receivedMessage <- expectMsgPF(actorRef, 1 second) {
          case s: String if s == "Hello" => ()
        }.as(true).handleError(_ => false)
      } yield receivedMessage should be(false)
    }
  }

  "receiveWhile" should "stop when an unhandled message is received" in {
    testActorSystem { case (_, actorRef) =>
      for {
        _ <- actorRef ! "Bye"
        _ <- actorRef ! "Bye"
        _ <- actorRef ! 2
        _ <- actorRef ! "Bye"

        receivedMessage <- receiveWhile(actorRef, 1 second) { case s: String =>
          s
        }
      } yield {
        receivedMessage should have size 2
        receivedMessage should contain theSameElementsAs Vector("Bye", "Bye")
      }
    }
  }

  "within" should "execute code block within given time bounds" in {
    testActorSystem(Actor.withReceive[IO, Any] { case s =>
      IO.sleep(150 millis).as(s)
    }) { case (_, actorRef) =>
      within(100 millis, 300 millis)(
        actorRef ? "Delayed Echo"
      ).map(_ should be("Delayed Echo"))
    }
  }

  "receiveWhile" should "stop without error when timeout is exceeded" in {
    testActorSystem { case (_, actorRef) =>
      for {
        _ <- actorRef ! "Bye"
        _ <- actorRef ! "Bye"
        _ <- actorRef ! "Bye"

        receivedMessage <- receiveWhile(actorRef, 1 second) { case s: String =>
          s
        }
      } yield {
        receivedMessage should have size 3
        receivedMessage should contain theSameElementsAs Vector("Bye", "Bye", "Bye")
      }
    }
  }

  "within" should "raise exception when code block takes longer than max time" in {
    testActorSystem(Actor.withReceive[IO, Any] { case s =>
      IO.sleep(400 millis).as(s)
    }) { case (_, actorRef) =>
      within(100 millis, 300 millis)(
        actorRef ? "Delayed Echo"
      ).as("Within Successful")
        .recover { case _: TimeoutException =>
          "Within Timed Out"
        }
        .map(_ should be("Within Timed Out"))
    }
  }

  "within" should "raise exception when code block takes less than min time" in {
    testActorSystem(Actor.ignoring[IO, Any]) { case (_, actorRef) =>
      within(100 millis, 300 millis)(
        actorRef ? "Delayed Echo"
      ).as("Within Successful")
        .recover { case _: Exception =>
          "Within Too Fast"
        }
        .map(_ should be("Within Too Fast"))
    }
  }
}
