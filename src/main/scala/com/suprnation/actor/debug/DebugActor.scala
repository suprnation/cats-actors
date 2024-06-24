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

package com.suprnation.actor.debug

import cats.Parallel
import cats.effect.{Concurrent, Temporal}
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.{ActorLogging, ReplyingActor, ReplyingActorRef}

case class DebugActor[F[+_]: Parallel: Concurrent: Temporal, Request](
    input: ReplyingActorRef[F, Request, Any]
) extends ReplyingActor[F, Request, Any]
    with ActorLogging[F, Request] {
  override def receive: ReplyingReceive[F, Request, Any] = { case msg =>
    log(
      s"~~ redirect ~~>: [Name: ${input.path.name}] [Path: ${input.path}]] ${msg.toString}"
    ) >> (input ? msg)
  }
}
