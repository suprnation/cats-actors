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

package com.suprnation.typelevel.actors.syntax
import cats.Parallel
import cats.effect.syntax.all._
import cats.effect.{Concurrent, Deferred, Temporal}
import cats.implicits._
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.{ActorRef, ActorSystem}

import scala.concurrent.duration._
import scala.language.postfixOps

object ActorRefDebugSyntax {
  def waitForIdleDeferredOnIdleF[F[_]: Temporal](isIdle: F[Boolean]): F[Deferred[F, Unit]] =
    Deferred[F, Unit].flatMap { deferred =>
      // This function will continuously check if the system is idle and complete the Deferred once it is
      def waitForIdle: F[Unit] =
        for {
          idle <- isIdle
          _ <-
            if (idle) {
              deferred.complete(()).void // Complete the Deferred when the system is idle
            } else {
              Temporal[F].sleep(100 millis) >> waitForIdle // Wait for a bit and check again
            }
        } yield ()
      waitForIdle.start.as(
        deferred
      ) // Start the waiting process in a separate fiber and return the Deferred
    }
}

trait ActorRefDebugSyntax {

  import ActorRefDebugSyntax._

  final implicit class DebugActorRefSyntaxOps[F[+_]: Temporal](actorRef: ActorRef[F]) { self =>
    def waitForIdle: F[Unit] =
      (actorRef.cell >>= ((c: ActorCell[F]) => waitForIdleDeferredOnIdleF(c.isIdle))).flatMap(_.get)
  }

  final implicit class DebugListActorRefSyntaxOps[F[+_]: Concurrent: Parallel: Temporal](
      actorRefs: List[ActorRef[F]]
  ) { self =>
    val systemsF: F[List[ActorSystem[F]]] =
      actorRefs
        .map((actorRef: ActorRef[F]) => actorRef.cell.map(_.system))
        .sequence

    def waitForIdle: F[Unit] =
      actorRefs.waitForIdleDeferred.flatMap(_.get)

    def waitForIdleDeferred: F[Deferred[F, Unit]] =
      for {
        systems <- systemsF
        system = systems.head
        deferred <-
          if (systems.distinct.size != 1) {
            Concurrent[F].raiseError(
              new IllegalStateException(
                "ActorRef systems are not the same, please make sure all actors are owned by the same system"
              )
            )
          } else {
            Deferred[F, Unit].flatMap { systemIdle =>
              def waitForIdle: F[Unit] =
                for {
                  _ <- actorRefs.parTraverse_(_.waitForIdle)
                  _ <- system.scheduler.isIdle.ifM(
                    // If the scheduler is idle we simply return
                    Concurrent[F].unit,
                    // Scheduler is not idle so wait for the scheduler to clear, but in return the scheduled messages might have created more messages which scheduler other messages
                    // an so on and so forth..
                    waitForIdleDeferredOnIdleF(system.scheduler.isIdle)
                      .flatMap(_.get) >> waitForIdle
                  )
                  _ <- systemIdle.complete(()).void
                } yield ()
              // Run this in the background...
              waitForIdle.start.as(systemIdle)
            }
          }
      } yield deferred
  }
}
