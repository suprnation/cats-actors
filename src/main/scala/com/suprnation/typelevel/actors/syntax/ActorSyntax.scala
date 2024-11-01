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
import cats.effect.{Concurrent, Ref, Temporal}
import cats.implicits.toFlatMapOps
import com.suprnation.actor.ReplyingActor
import com.suprnation.actor.debug.TrackingActor

trait ActorSyntax {
  implicit class ActorSyntaxFOps[
      F[+_]: Parallel: Concurrent: Temporal,
      Request,
      Response
  ](fA: ReplyingActor[F, Request, Response]) {
    import TrackingActor.ActorRefs

    def track(name: String)(implicit
        cache: Ref[F, Map[String, ActorRefs[F]]]
    ): F[TrackingActor[F, Request, Response]] =
      TrackingActor.create[F, Request, Response](cache, name, fA)

    def trackWithCache(name: String): F[TrackingActor[F, Request, Response]] =
      Ref.of[F, Map[String, ActorRefs[F]]](Map.empty).flatMap { cache =>
        TrackingActor.create[F, Request, Response](cache, name, fA)
      }
  }
}
