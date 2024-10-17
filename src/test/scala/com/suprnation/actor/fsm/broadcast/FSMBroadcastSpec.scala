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

package com.suprnation.actor
package fsm
package broadcast

import cats.effect.unsafe.implicits.global
import cats.implicits._
import cats.effect.{IO, Ref}
import com.suprnation.actor.broadcast.TreenodeActor
import com.suprnation.actor.broadcast.TreenodeActor._
import com.suprnation.actor.fsm.broadcast.TreenodeFsmActor
import com.suprnation.actor.fsm.broadcast.TreenodeFsmActor.TreenodeData
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.utils.Typechecking.TypecheckException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike

import scala.collection.immutable.Queue
import scala.concurrent.duration._

class FSMBroadcastSpec extends AsyncWordSpecLike with Matchers with TestKit {
  val numberOfChildren = 4
  val numberOfGrandChildren = 2

  "Calling broadcast on parent" should {
    "broadcast message to children" in {
      ActorSystem[IO]("BroadcastFSMTest")
        .use { system =>
          for {
            data <- Ref[IO].of(TreenodeData(Queue()))
            children <-
              List
                .range(1, numberOfChildren)
                .parTraverse(i =>
                  TreenodeFsmActor.create(system, s"a-1-$i", 100.millis, 1.seconds, None)()
                )
            a1 <- TreenodeFsmActor.create(system, "a-1", 0.seconds, 0.seconds, Some(data))(
              children: _*
            )

            response <- within(2.seconds)(a1 ? Ping)
            dataResult <- data.get

          } yield (response, dataResult.requests)
        }
        .unsafeToFuture()
        .map { case (response, data) =>
          response should equal(Pong(Queue.range(1, numberOfChildren).map(i => s"a-1-$i") :+ "a-1"))
          data should equal(List.range(1, numberOfChildren).map(i => s"a-1-$i"))
        }
    }

    "broadcast messages to children (2 levels)" in {
      ActorSystem[IO]("BroadcastFSMTest")
        .use { system =>
          for {
            data <- Ref[IO].of(TreenodeData(Queue()))
            children <- List
              .range(1, numberOfChildren)
              .traverse(i =>
                List
                  .range(1, numberOfGrandChildren)
                  .parTraverse(j =>
                    TreenodeFsmActor.create(system, s"b-1-$i-$j", 100.millis, 1.seconds, None)()
                  ) >>= { grandchildren =>
                  TreenodeFsmActor.create(system, s"b-1-$i", 0.seconds, 0.seconds, None)(
                    grandchildren: _*
                  )
                }
              )
            b1 <- TreenodeFsmActor.create(system, "b-1", 0.seconds, 0.seconds, Some(data))(
              children: _*
            )

            response <- within(2.seconds)(b1 ? Ping)
            dataResult <- data.get

          } yield (response, dataResult.requests)
        }
        .unsafeToFuture()
        .map { case (response, data) =>
          response should equal(
            Pong(
              Queue
                .range(1, numberOfChildren)
                .flatMap(i =>
                  Queue.range(1, numberOfGrandChildren).map(j => s"b-1-$i-$j") :+ s"b-1-$i"
                ) :+ "b-1"
            )
          )
          data should equal(
            List
              .range(1, numberOfChildren)
              .flatMap(i =>
                List.range(1, numberOfGrandChildren).map(j => s"b-1-$i-$j") :+ s"b-1-$i"
              )
          )
        }

    }

    "escalate error properly" in {
      recoverToSucceededIf[TreenodeActor.Error.type] {
        ActorSystem[IO]("BroadcastFSMTest")
          .use { system =>
            for {
              child <- TreenodeFsmActor.create(system, "child", 0.seconds, 0.seconds, None)()
              error <- TreenodeFsmActor.create(system, "error", 0.seconds, 0.seconds, None, Error)()
              broadcaster <- TreenodeFsmActor.create(
                system,
                "broadcaster",
                0.seconds,
                0.seconds,
                None
              )(child, error)

              response <- within(2.seconds)(broadcaster ? Ping)

            } yield ()
          }
          .unsafeToFuture()
      }
    }

    "raise an exception on a response of the wrong type" in {
      recoverToSucceededIf[TypecheckException[Response, Pong]] {
        ActorSystem[IO]("BroadcastFSMTest")
          .use { system =>
            for {
              child <- TreenodeFsmActor.create(system, "child", 0.seconds, 0.seconds, None)()
              error <- TreenodeFsmActor.create(system, "error", 0.seconds, 0.seconds, None, Wrong)()
              broadcaster <- TreenodeFsmActor.create(
                system,
                "broadcaster",
                0.seconds,
                0.seconds,
                None
              )(child, error)

              response <- within(2.seconds)(broadcaster ? Ping)

            } yield ()
          }
          .unsafeToFuture()
      }
    }
  }

}
