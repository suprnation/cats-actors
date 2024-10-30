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

import cats.{Applicative, MonadThrow, Parallel, Traverse}
import cats.implicits._
import cats.effect.Concurrent
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ReplyingActorRef
import com.suprnation.typelevel.actors.syntax.TypecheckSyntax._
import scala.reflect.ClassTag
import com.suprnation.actor.broadcast.{Broadcast, BroadcastAsk}

trait BroadcastSyntax {
  final implicit class BroadcastOps[F[+_]: Applicative, G[+_]: Traverse, Request, Response](
      actorRefs: G[ReplyingActorRef[F, Request, Response]]
  ) {

    /** Broadcast: tell a message in sequence */
    def broadcast(message: => Request)(implicit
        sender: Option[ActorRef[F, Nothing]] = None
    ): Broadcast[F, G, Request] = Broadcast[F, G, Request](actorRefs, message)

    /** Broadcast: tell a message in sequence */
    @inline def !(message: => Request)(implicit
        sender: Option[ActorRef[F, Nothing]] = None
    ): Broadcast[F, G, Request] = broadcast(message)
  }

  final implicit class BroadcastAskOps[F[+_]: MonadThrow, G[+_]: Traverse, Request, Response](
      actorRefs: G[ReplyingActorRef[F, Request, Response]]
  ) {

    /** Broadcast: ask a message */
    def broadcastAsk(message: => Request)(implicit
        sender: Option[ActorRef[F, Nothing]] = None
    ): BroadcastAsk[F, G, Request, Response, Response] = BroadcastAsk(actorRefs, message)

    /** Broadcast: ask a message in sequence */
    @inline def ?(message: => Request)(implicit
        sender: Option[ActorRef[F, Nothing]] = None
    ): BroadcastAsk[F, G, Request, Response, Response] = broadcastAsk(message)
  }

}

object BroadcastSyntax extends BroadcastSyntax
