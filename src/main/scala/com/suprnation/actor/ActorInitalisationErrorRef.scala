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

import cats.MonadThrow
import cats.effect.std.Console
import cats.effect.{Concurrent, Ref, Temporal}
import cats.implicits._

import scala.concurrent.duration._
import scala.language.postfixOps

/** Streamline accessing of Refs which have an Option[A].
  *
  * Keep this as an opaque class so we do not get a performance penalty.
  */
case class ActorInitalisationError[A](value: Option[A]) extends AnyVal {

  def getOrWait[F[+_]: Temporal: Console]: F[A] =
    value.fold(Console[F].println("Cell is empty!") >> Temporal[F].sleep(1 second) >> getOrWait)(
      _.pure[F]
    )

  def get[F[+_]: Concurrent]: F[A] =
    value.fold(MonadThrow[F].raiseError[A](ReplyingActor.ACTOR_NOT_INITIALIZED))(_.pure[F])
}

/** Streamline accessing of Refs which have an Option[A] to A transformation and which also imply that not being able to do such transformation is a result of Actor initialisation failure.
  *
  * Keep this as an opaque class so we do not get a performance penalty.
  */
case class ActorInitalisationErrorRef[F[_]: Temporal: Console, A](
    underlyingRef: Ref[F, Option[A]]
) {

  def getOrWait: F[A] =
    for {
      value <- underlyingRef.get
      result <- value match {
        case Some(a) => a.pure[F]
        case _       => Temporal[F].sleep(1 second) >> getOrWait
      }
    } yield result

  def get: F[A] =
    for {
      value <- underlyingRef.get
      result <- value match {
        case Some(a) => a.pure[F]
        case _       => MonadThrow[F].raiseError(ReplyingActor.ACTOR_NOT_INITIALIZED)
      }
    } yield result

  def getAndUpdate(f: A => A): F[A] =
    for {
      result <- underlyingRef.getAndUpdate(current => current.map(f))
      result <- result match {
        case Some(x) => x.pure[F]
        case None    => MonadThrow[F].raiseError(ReplyingActor.ACTOR_NOT_INITIALIZED)
      }
    } yield result

  def getAndSet(a: A): F[A] =
    for {
      result <- underlyingRef.getAndSet(a.some)
      result <- result match {
        case Some(x) => x.pure[F]
        case None    => MonadThrow[F].raiseError(ReplyingActor.ACTOR_NOT_INITIALIZED)
      }
    } yield result

  def set(a: A): F[Unit] = underlyingRef.set(a.some)

  def update(f: A => A): F[Unit] = underlyingRef.update {
    case Some(value) => f(value).some
    case None        => None
  }
}
