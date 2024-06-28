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
import cats.effect.{Concurrent, Temporal}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.debug.DebugActor
import com.suprnation.actor.{ActorRefProvider, ReplyingActor}

import java.util.UUID

trait DebugActorSyntax {

  final implicit class DebugActorSytnaxOps[F[+_]: Parallel: Concurrent: Temporal](
      factory: ActorRefProvider[F]
  ) {
    def actorOfWithDebug[Request, Response](
        props: => ReplyingActor[F, Request, Response],
        name: => String
    ): F[ActorRef[F, Request]] =
      actorOfWithDebug[Request, Response](props.pure[F], name)

    def actorOfWithDebug[Request, Response](
        props: => ReplyingActor[F, Request, Response]
    ): F[ActorRef[F, Request]] =
      actorOfWithDebug[Request, Response](props.pure[F], UUID.randomUUID().toString)

    /** Creates the actor and wraps it up within a [[com.suprnation.actor.debug.DebugActor]]. The [[com.suprnation.actor.debug.DebugActor]] listens to all messages and forwards all messages to the underlying actor.
      *
      * @param name
      *   the name of the actor (the underlying actor name will be name) the debug wrapping actor will be debug-name)
      * @return
      */
    def actorOfWithDebug[Request, Response](
        props: F[ReplyingActor[F, Request, Response]],
        name: String
    ): F[ActorRef[F, Request]] =
      for {
        actor <- factory.replyingActorOf[Request, Response](props, name).map(_.widenResponse[Any])
        result <- factory.actorOf[Request](DebugActor(actor), s"debug-$name")
      } yield result
  }
}
