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

package com.suprnation.actor.broadcast

import cats.effect.unsafe.implicits.global
import cats.implicits._
import cats.effect.IO
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.broadcast.TreenodeActor
import com.suprnation.actor.broadcast.TreenodeActor.{Ping, Pong, Response}
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.utils.Typechecking.TypecheckException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.concurrent.duration._
import scala.collection.immutable.Queue

class BroadcastSpec extends AsyncWordSpecLike with Matchers with TestKit {

  "Calling broadcast on parent" should {
    "broadcast message to children asynchronously" in {
      ActorSystem[IO]("BroadcastCatsActorTest")
        .use { system =>
          for {
            children <- List
              .range(1, 10)
              .traverse(i => system.replyingActorOf(TreenodeActor(s"a-1-$i", 0.seconds)()))
            a1 <- system.replyingActorOf(TreenodeActor("a-1", 0.seconds)(children: _*))

            response <- within(2.seconds)(a1 ? Ping)

          } yield response
        }
        .unsafeToFuture()
        .map { response =>
          response should equal(Pong(Queue.range(1, 10).map(i => s"a-1-$i") :+ "a-1"))
        }
    }

    "broadcast messages to children (2 level) asynchronously " in {
      ActorSystem[IO]("BroadcastCatsActorTest")
        .use { system =>
          for {
            children <- List
              .range(1, 10)
              .traverse(i =>
                List
                  .range(1, 10)
                  .traverse(j =>
                    system.replyingActorOf(TreenodeActor(s"b-1-$i-$j", 0.seconds)())
                  ) >>= { grandchildren =>
                  system.replyingActorOf(TreenodeActor(s"b-1-$i", 0.seconds)(grandchildren: _*))
                }
              )
            b1 <- system.replyingActorOf(TreenodeActor("b-1", 0.seconds)(children: _*))

            response <- within(2.seconds)(b1 ? Ping)

          } yield response
        }
        .unsafeToFuture()
        .map { response =>
          response should equal(
            Pong(
              Queue
                .range(1, 10)
                .flatMap(i => Queue.range(1, 10).map(j => s"b-1-$i-$j") :+ s"b-1-$i") :+ "b-1"
            )
          )
        }

    }

    "escalate error properly" in {
      recoverToSucceededIf[TreenodeActor.Error.type] {
        ActorSystem[IO]("BroadcastCatsActorTest")
          .use { system =>
            for {
              child <- system.replyingActorOf(TreenodeActor("child", 0.seconds)())
              error <- system.replyingActorOf(
                TreenodeActor("error", 0.seconds, TreenodeActor.Error)()
              )
              broadcaster <- system.replyingActorOf(
                TreenodeActor("broadcaster", 0.seconds)(child, error)
              )

              response <- within(2.seconds)(broadcaster ? Ping)

            } yield ()
          }
          .unsafeToFuture()
      }
    }

    "raise an exception on a response of the wrong type" in {
      recoverToSucceededIf[TypecheckException[Response, Pong]] {
        ActorSystem[IO]("BroadcastCatsActorTest")
          .use { system =>
            for {
              child <- system.replyingActorOf(TreenodeActor("child", 0.seconds)())
              error <- system.replyingActorOf(
                TreenodeActor("error", 0.seconds, TreenodeActor.Wrong)()
              )
              broadcaster <- system.replyingActorOf(
                TreenodeActor("broadcaster", 0.seconds)(child, error)
              )

              response <- within(2.seconds)(broadcaster ? Ping)

            } yield ()
          }
          .unsafeToFuture()
      }
    }
  }

}
