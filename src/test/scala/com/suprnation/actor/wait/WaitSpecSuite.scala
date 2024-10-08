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

package com.suprnation.actor.wait

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.wait.WaitSpecSuite.{slowActor, slowActorWithForward}
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object WaitSpecSuite {

  def slowActor(ref: Ref[IO, Boolean]): Actor[IO, String] = new Actor[IO, String] {
    override def receive: Receive[IO, String] = { case msg =>
      IO.sleep(10 millisecond) >> ref.set(true).as(msg)
    }
  }

  def slowActorWithForward(ref: Ref[IO, Boolean], levels: Int = 1): Actor[IO, String] =
    new Actor[IO, String] {
      override def receive: Receive[IO, String] =
        if (levels == 1) {
          case "slow" =>
            IO.sleep(10 millisecond) >> ref.updateAndGet(_ => true)
          case "schedule" =>
            IO.sleep(10 millisecond) >> ref.updateAndGet(_ => true)
        }
        else {
          case "slow" =>
            for {
              slowActor <- context.actorOfWithDebug(
                slowActorWithForward(ref, levels - 1),
                s"${context.self.path.name.split("-").head}-${levels - 1}"
              )
              _ <- slowActor ! "slow"
            } yield ()
          case "schedule" =>
            context
              .actorOfWithDebug(
                slowActorWithForward(ref, levels - 1),
                s"${context.self.path.name.split("-").head}-${levels - 1}"
              )
              .flatMap(_ ! "schedule")

        }
    }
}

class WaitSpecSuite extends AsyncFlatSpec with Matchers {

  it should "be able to wait for messages to be processed" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOf[String](slowActor(ref), "waiting")
      _ <- (input ! "slow").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }

  }

  it should "be able to wait for messages to be processed when it has scheduled messages" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOf[String](slowActor(ref), "waiting")
      _ <- (input ! "schedule").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed (debugger)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(
        slowActor(ref),
        "waiting"
      )
      _ <- (input ! "slow").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed when it has scheduled messages (debugger)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(
        slowActor(ref),
        "waiting"
      )
      _ <- (input ! "schedule").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed (children)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(
        slowActor(ref),
        "waiting"
      )
      _ <- input ! "slow"
      // Print all children so we are sure!
      names <- system.allChildren >>= (children => children.traverse(_.path.name.pure[IO]))
      _ <- system.waitForIdle()
      result <- ref.get
    } yield (names, result)).unsafeToFuture().map { case (names, result) =>
      assert(result)
      assert(names.toSet == Set("user", "waiting", "debug-waiting"))
    }
  }

  it should "be able to wait for messages to be processed recursively" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(
        slowActorWithForward(ref, 3),
        "waiting-3"
      )
      _ <- input ! "slow" // assert that at least the first actor received it in his queue.
      names <- system.waitForIdle() >>= (children => children.traverse(c => c.path.name.pure[IO]))
      result <- ref.get
    } yield (names, result)).unsafeToFuture().map { case (names, result) =>
      assert(result)
      assert(
        names.toSet == Set(
          "user",
          "waiting-1",
          "debug-waiting-1",
          "waiting-2",
          "debug-waiting-2",
          "debug-waiting-3",
          "waiting-3"
        )
      )
    }
  }

}
