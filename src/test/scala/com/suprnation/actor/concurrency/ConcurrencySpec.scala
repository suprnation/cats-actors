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

package com.suprnation.actor.concurrency

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.concurrency.ConcurrencySpec.ConcurrentActorTest
import com.suprnation.actor.ref.UnsafeRef
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object ConcurrencySpec {

  class ConcurrentActorTest(
      incrA: UnsafeRef[IO, Int],
      incrB: UnsafeRef[IO, Int],
      incrC: UnsafeRef[IO, Int]
  ) extends ReplyingActor[IO, String, Int] {
    override def receive: ReplyingReceive[IO, String, Int] = {
      case "a" => incrA.updateAndGet(_ + 1)
      case "b" => incrB.updateAndGet(_ + 1)
      case "c" => incrC.updateAndGet(_ + 1)
    }
  }
}

class ConcurrencySpec extends AsyncFlatSpec with Matchers {

  it should "allow multiple messages from different actors and these should not be lost.  " in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      incrA <- UnsafeRef.of[IO, Int](0)
      incrB <- UnsafeRef.of[IO, Int](0)
      incrC <- UnsafeRef.of[IO, Int](0)

      calculator <- system.replyingActorOf(new ConcurrentActorTest(incrA, incrB, incrC))
      _ <- system.actorOf[Nothing](new Actor[IO, Nothing] {
        override def preStart: IO[Unit] = (calculator ! "a").replicateA_(10000).start.void
      })
      _ <- system.actorOf[Nothing](new Actor[IO, Nothing] {
        override def preStart: IO[Unit] = (calculator ! "b").replicateA_(10000).start.void
      })
      _ <- system.actorOf[Nothing](new Actor[IO, Nothing] {
        override def preStart: IO[Unit] = (calculator ! "c").replicateA_(10000).start.void
      })

      _ <- system.waitForIdle()
      a <- incrA.get
      b <- incrB.get
      c <- incrC.get

    } yield (a, b, c)).unsafeToFuture().map { case (a, b, c) =>
      a should be(10000)
      b should be(10000)
      c should be(10000)
    }
  }
}
