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

package com.suprnation

import cats.Parallel
import cats.effect._
import cats.effect.std._
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.SupervisorStrategy.Escalate
import com.suprnation.actor.{ActorContext, AllForOneStrategy, ReplyingActor, SupervisionStrategy}

import scala.concurrent.duration._
import scala.language.postfixOps

object EscalatingReplyingActor {
  def apply[F[+_]: Async: Parallel: Temporal: Console, Request, Response](
      _preStart: ActorContext[F, Request, Response] => F[Unit],
      _receive: ActorContext[F, Request, Response] => ReplyingReceive[F, Request, Response]
  ): ReplyingActor[F, Request, Response] =
    new EscalatingReplyingActor[F, Request, Response] {
      override def preStart: F[Unit] = _preStart(context)
      override def receive: ReplyingReceive[F, Request, Response] = _receive(context)
    }

  def apply[F[+_]: Async: Parallel: Temporal: Console, Request, Response](
      _receive: ActorContext[F, Request, Response] => ReplyingReceive[F, Request, Response]
  ): ReplyingActor[F, Request, Response] =
    new EscalatingReplyingActor[F, Request, Response] {
      override def receive: ReplyingReceive[F, Request, Response] = _receive(context)
    }

  val ACTOR_NOT_INITIALIZED = new Error(
    "Actor not set.  Possibly actor has been stopped or not initialised.  "
  )

  def preStart[F[+_]: Async: Parallel: Temporal: Console](
      _preStart: ActorContext[F, Nothing, Any] => F[Unit]
  ): ReplyingActor[F, Nothing, Any] =
    new EscalatingReplyingActor[F, Nothing, Any] {
      override def preStart: F[Unit] = _preStart(context)
    }

  def postStop[F[+_]: Async: Parallel: Temporal: Console](
      _postStop: ActorContext[F, Nothing, Any] => F[Unit]
  ): ReplyingActor[F, Nothing, Any] =
    new EscalatingReplyingActor[F, Nothing, Any] {
      override def postStop: F[Unit] = _postStop(context)
    }
}

abstract class EscalatingActor[F[+_]: Console: Async: Parallel: Temporal, Request]
    extends EscalatingReplyingActor[F, Request, Any] {}

abstract class EscalatingReplyingActor[F[+_]: Console: Async: Parallel: Temporal, Request, Response]
    extends ReplyingActor[F, Request, Response] {
  override def supervisorStrategy: SupervisionStrategy[F] =
    AllForOneStrategy[F](maxNrOfRetries = 0, withinTimeRange = 1 minute) { case _: Throwable =>
      Escalate
    }

  override def preSuspend(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    (reason, message) match {
      // This is the actor that was directly responsible for the problem.  The message is filled in so we know that this actor is the cause.
      case (Some(t), Some(m)) =>
        Console[F].println(
          s"Actor [${this.self.path}] has been suspended due [Error: $t] [Message: $m]"
        ) >>
          Sync[F].blocking(t.printStackTrace())
      case _ => Concurrent[F].unit
    }

}
