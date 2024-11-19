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

package com.suprnation.actor.broadcast

import cats._
import cats.implicits._
import cats.effect.Concurrent
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ReplyingActorRef
import com.suprnation.typelevel.actors.syntax.TypecheckSyntax._

import scala.reflect.ClassTag

final case class Broadcast[F[+_]: Applicative, G[_]: Traverse, Request](
    actorRefs: G[ReplyingActorRef[F, Request, ?]],
    request: Request
)(implicit sender: Option[ActorRef[F, Nothing]] = None) {

  lazy val sequence: F[Unit] = actorRefs.traverse(ref => ref ! request).as(())

  def parallel(implicit parallel: Parallel[F]): F[Unit] =
    actorRefs.parTraverse(ref => ref ! request).as(())

}

final case class BroadcastAsk[F[+_]: MonadThrow, G[_]: Traverse, Request, Response, Result](
    actorRefs: G[ReplyingActorRef[F, Request, Response]],
    request: Request,
    mapF: Response => F[Result]
)(implicit sender: Option[ActorRef[F, Nothing]] = None) {

  /** Typecheck and cast each response */
  def expecting[T: ClassTag]: BroadcastAsk[F, G, Request, Response, T] =
    copy(mapF = mapF.andThen(_.narrow[T]))

  /** Map each response */
  def map[T](map: Result => T): BroadcastAsk[F, G, Request, Response, T] =
    copy(mapF = mapF.andThen(_.map(map)))

  /** Flatmap each response */
  def flatMap[T](map: Result => F[T]): BroadcastAsk[F, G, Request, Response, T] =
    copy(mapF = mapF.andThen(_.flatMap(map)))

  /** Partially map each response to another response type (or exception) */
  def mapping(
      mapF: PartialFunction[Result, F[Result]]
  ): BroadcastAsk[F, G, Request, Response, Result] =
    flatMap(mapF.applyOrElse(_, MonadThrow[F].pure[Result]))

  /** MapFilter each response */
  def mapFilter[T](map: Result => Option[T])(implicit
      FF: FunctorFilter[F]
  ): BroadcastAsk[F, G, Request, Response, T] =
    copy(mapF = mapF.andThen(_.mapFilter(map)))

  /** Execute the broadcast in sequence */
  lazy val sequence: F[G[Result]] = actorRefs.traverse(ref => (ref ? request).flatMap(mapF))

  /** Execute the broadcast in parallel */
  def parallel(implicit parallel: Parallel[F]): F[G[Result]] =
    actorRefs.parTraverse(ref => (ref ? request).flatMap(mapF))

}

object BroadcastAsk extends BroadcastAskInstances {
  def apply[F[+_]: MonadThrow, G[_]: Traverse, Request, Response](
      actorRefs: G[ReplyingActorRef[F, Request, Response]],
      request: Request
  ): BroadcastAsk[F, G, Request, Response, Response] =
    BroadcastAsk(actorRefs, request, MonadThrow[F].pure)
}

trait BroadcastAskInstances extends BroadcastAskInstances0 {

  implicit def functorFilterForBroadcastAsk[F[+_]: FunctorFilter, G[_], Request, Response]
      : FunctorFilter[BroadcastAsk[F, G, Request, Response, _]] =
    new BroadcastAskFunctorFilter[F, G, Request, Response]

}

trait BroadcastAskInstances0 {

  implicit def functorForBroadcastAsk[F[+_], G[_], Request, Response]
      : Functor[BroadcastAsk[F, G, Request, Response, _]] =
    new BroadcastAskFunctor[F, G, Request, Response]

}

sealed private[this] class BroadcastAskFunctor[F[+_], G[_], Request, Response]
    extends Functor[BroadcastAsk[F, G, Request, Response, _]] {

  override def map[A, B](fa: BroadcastAsk[F, G, Request, Response, A])(
      f: A => B
  ): BroadcastAsk[F, G, Request, Response, B] =
    fa.map(f)
}

sealed private[this] class BroadcastAskFunctorFilter[F[+_]: FunctorFilter, G[_], Request, Response]
    extends FunctorFilter[BroadcastAsk[F, G, Request, Response, _]] {

  override val functor: Functor[BroadcastAsk[F, G, Request, Response, _]] =
    BroadcastAsk.functorForBroadcastAsk

  override def mapFilter[A, B](fa: BroadcastAsk[F, G, Request, Response, A])(
      f: A => Option[B]
  ): BroadcastAsk[F, G, Request, Response, B] =
    fa.mapFilter(f)
}
