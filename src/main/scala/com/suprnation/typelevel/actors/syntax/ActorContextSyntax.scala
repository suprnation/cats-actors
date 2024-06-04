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

import cats.effect.syntax.all._
import cats.effect.{Concurrent, Temporal}
import cats.implicits._
import com.suprnation.actor.ActorContext

import scala.util.Try

trait ActorContextSyntax {

  implicit class ActorContextOps[F[+_]: Concurrent: Temporal](actorContext: ActorContext[F]) {
    def pipeToSelfAsync[A, B](fa: => F[A])(mapResult: Try[A] => F[B]): F[Unit] =
      pipeToSelf(fa)(mapResult).start.void

    def pipeToSelf[A, B](fa: => F[A])(mapResult: Try[A] => F[B]): F[Unit] =
      fa.attempt.map(_.toTry).flatMap(mapResult) >>= (actorContext.self ! _)

  }
}
