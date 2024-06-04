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
import cats.effect.std.Console
import cats.effect.{Concurrent, Temporal}
import cats.implicits._
import com.suprnation.actor.{ActorRef, ActorSystem}

import scala.concurrent.duration._
import scala.language.postfixOps

trait ActorSystemDebugSyntax {

  final implicit class ActorSystemDebugOps[F[+_]: Concurrent: Parallel: Temporal: Console](
      actorSystem: ActorSystem[F]
  ) {
    def waitForIdle(maxTimeout: Duration = 30 second): F[List[ActorRef[F]]] =
      Concurrent[F]
        .race(
          for {
            children <- actorSystem.allChildren
            _ <- children.waitForIdle
            // Just a little delay so we let the system stabilise a bit before we check again
            _ <- Temporal[F].sleep(100 milliseconds)
            // After waiting for all the children, have new actors been created?
            deadLetters <- actorSystem.deadLetters
            newChildren <- actorSystem.allChildren
            oldSet = children.map(_.path).toSet
            newSet = newChildren.map(_.path).toSet
            _ <- (oldSet == newSet)
              .pure[F]
              .ifM(
                Temporal[F].unit,
                waitForIdle(maxTimeout).void
              )
            _ <- deadLetters.waitForIdle
            _ <- actorSystem.isTerminated.ifM(
              new IllegalStateException("System has been terminated").raiseError[F, Unit],
              Temporal[F].unit
            )
          } yield newChildren,
          Temporal[F].sleep(maxTimeout)
        )
        .flatMap {
          case Left(result) =>
            result.pure[F]
          case Right(_) =>
            for {
              children <- actorSystem.allChildren
              childList <- children
                .map(actorRef =>
                  for {
                    cell <- actorRef.cell
                    idle <- cell.isIdle
                  } yield s"${actorRef.path} => ${idle}"
                )
                .sequence
              result <- Concurrent[F].raiseError[List[ActorRef[F]]](
                new IllegalStateException(
                  s"""Wait timeout exceeded, consider re-writing the test so that its not so slow.  If this is expected behaviour increase the maxTimeout
                       |
                       | ${childList.mkString("\n")}
                       |""".stripMargin
                )
              )
            } yield result

        }

    def allChildren: F[List[ActorRef[F]]] =
      actorSystem.guardian.get >>= (userGuardian =>
        userGuardian.allChildrenFromThisActor.map(actors => userGuardian :: actors)
      )

  }

}
