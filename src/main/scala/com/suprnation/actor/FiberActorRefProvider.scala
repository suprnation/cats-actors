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
import cats.effect.std.{Console, Supervisor}
import cats.effect.{Async, Deferred, Temporal}
import cats.implicits._
import cats.Applicative
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.engine.ActorCell

import java.util.UUID

/** Interface by the ActorSystem and ActorContext, the only two places from which you can get a fresh actor.
  */
trait FiberActorRefProvider[F[+_]] {

  /** Create new actor as child of this context and give it a name.
    */
  def actorOf[Request](props: => Actor[F, Request])(implicit
      applicativeEvidence: Applicative[F]
  ): F[InternalActorRef[F, Request, Any]] =
    replyingActorOf[Request, Any](props)

  /** Create new actor as child of this context and give it a name.
    */
  def actorOf[Request](props: => Actor[F, Request], name: => String)(implicit
      applicativeEvidence: Applicative[F]
  ): F[InternalActorRef[F, Request, Any]] =
    replyingActorOf[Request, Any](props, name)

  /** Creates new actor as a child of this context with the given name which must not be null, empty or start with "$". If the given name is already in use an [[InvalidActorNameException]] is thrown.
    *
    * @throws InvalidActorNameException
    *   if given name is invalid or already in use.
    * @return
    */
  def actorOf[Request](
      props: F[Actor[F, Request]],
      name: => String = UUID.randomUUID().toString
  ): F[InternalActorRef[F, Request, Any]] = replyingActorOf[Request, Any](props, name)

  /** Create new actor as child of this context and give it a name.
    */
  def replyingActorOf[Request, Response](props: => ReplyingActor[F, Request, Response])(implicit
      applicativeEvidence: Applicative[F]
  ): F[InternalActorRef[F, Request, Response]] =
    replyingActorOf[Request, Response](props, UUID.randomUUID().toString)

  /** Create new actor as child of this context and give it a name.
    */
  def replyingActorOf[Request, Response](
      props: => ReplyingActor[F, Request, Response],
      name: => String
  )(implicit
      applicativeEvidence: Applicative[F]
  ): F[InternalActorRef[F, Request, Response]] =
    replyingActorOf[Request, Response](props.pure[F], name)

  /** Creates new actor as a child of this context with the given name which must not be null, empty or start with "$". If the given name is already in use an [[InvalidActorNameException]] is thrown.
    *
    * @throws InvalidActorNameException
    *   if given name is invalid or already in use.
    * @return
    */
  def replyingActorOf[Request, Response](
      props: F[ReplyingActor[F, Request, Response]],
      name: => String = UUID.randomUUID().toString
  ): F[InternalActorRef[F, Request, Response]]
}

/** Interface by the ActorSystem and ActorContext, the only two places from which you can get a fresh actor.
  */
trait ActorRefProvider[F[+_]] {

  def actorOf[Request](actor: => Actor[F, Request])(implicit
      applicationF: Applicative[F]
  ): F[ActorRef[F, Request]] =
    replyingActorOf(actor)

  def actorOf[Request](actor: => Actor[F, Request], name: => String)(implicit
      applicationF: Applicative[F]
  ): F[ActorRef[F, Request]] =
    replyingActorOf(actor, name)

  def actorOf[Request](
      props: F[Actor[F, Request]],
      name: => String = UUID.randomUUID().toString
  ): F[ActorRef[F, Request]] = replyingActorOf[Request, Any](props, name)

  def replyingActorOf[Request, Response](actor: => ReplyingActor[F, Request, Response])(implicit
      applicationF: Applicative[F]
  ): F[ReplyingActorRef[F, Request, Response]] =
    replyingActorOf[Request, Response](actor.pure[F], UUID.randomUUID().toString)

  def replyingActorOf[Request, Response](
      actor: => ReplyingActor[F, Request, Response],
      name: => String
  )(implicit
      applicationF: Applicative[F]
  ): F[ReplyingActorRef[F, Request, Response]] =
    replyingActorOf[Request, Response](actor.pure[F], name)

  def replyingActorOf[Request, Response](
      props: F[ReplyingActor[F, Request, Response]],
      name: => String = UUID.randomUUID().toString
  ): F[ReplyingActorRef[F, Request, Response]]
}

class LocalActorRefProvider[F[+_]: Async: Temporal: Console](
    supervisor: Supervisor[F],
    systemShutdownSignal: Deferred[F, Unit],
    system: ActorSystem[F],
    parent: NoSendActorRef[F]
) extends FiberActorRefProvider[F] {
  override def replyingActorOf[Request, Response](
      props: F[ReplyingActor[F, Request, Response]],
      name: => String = UUID.randomUUID().toString
  ): F[InternalActorRef[F, Request, Response]] = {
    val childPath: ActorPath = new ChildActorPath(parent.path, name, ActorCell.newUid())
    InternalActorRef[F, Request, Response](
      supervisor,
      systemShutdownSignal,
      name,
      props,
      system,
      childPath,
      Some(parent)
    )
  }
}
