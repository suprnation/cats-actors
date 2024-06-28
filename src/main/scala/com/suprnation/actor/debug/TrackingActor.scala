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
import cats.effect.std.Console
import cats.effect.{Concurrent, Ref, Temporal}
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.utils.Unsafe
import com.suprnation.actor.{ActorConfig, ReplyingActor, SupervisionStrategy}

object TrackingActor {
  type ActorRefs[F[_]] = (
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, Int],
      Ref[F, List[Any]],
      Ref[F, List[(Option[Throwable], Option[Any])]],
      Ref[F, List[(Throwable, Option[Any])]]
  )

  def create[F[+_]: Parallel: Concurrent: Temporal: Console, Request, Response](
      cache: Ref[F, Map[String, ActorRefs[F]]],
      stableName: String,
      proxy: ReplyingActor[F, Request, Response]
  ): F[TrackingActor[F, Request, Response]] = {
    def newRefs: (
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, Int]],
        F[Ref[F, List[Any]]],
        F[Ref[F, List[(Option[Throwable], Option[Any])]]],
        F[Ref[F, List[(Throwable, Option[Any])]]]
    ) = (
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, Int](0),
      Ref.of[F, List[Any]](List.empty[Any]),
      Ref.of[F, List[(Option[Throwable], Option[Any])]](
        List.empty[(Option[Throwable], Option[Any])]
      ),
      Ref.of[F, List[(Throwable, Option[Any])]](List.empty[(Throwable, Option[Any])])
    )

    cache.get.flatMap { currentCache =>
      currentCache.get(stableName) match {
        case Some(refs) =>
          Concurrent[F].pure(
            new TrackingActor[F, Request, Response](
              refs._1,
              refs._2,
              refs._3,
              refs._4,
              refs._5,
              refs._6,
              refs._7,
              refs._8,
              refs._9,
              refs._10,
              proxy
            )
          )

        case None =>
          newRefs.mapN {
            case (
                  initCountRef,
                  preStartCountRef,
                  postStopCountRef,
                  preRestartCountRef,
                  postRestartCountRef,
                  preSuspendCountRef,
                  preResumeCountRef,
                  messageBufferRef,
                  restartMessageBufferRef,
                  errorMessageBufferRef
                ) =>
              cache.update(
                _ + (stableName -> (initCountRef, preStartCountRef, postStopCountRef, preRestartCountRef, postRestartCountRef, preSuspendCountRef, preResumeCountRef, messageBufferRef, restartMessageBufferRef, errorMessageBufferRef))
              ) *>
                Concurrent[F].pure(
                  new TrackingActor[F, Request, Response](
                    initCountRef,
                    preStartCountRef,
                    postStopCountRef,
                    preRestartCountRef,
                    postRestartCountRef,
                    preSuspendCountRef,
                    preResumeCountRef,
                    messageBufferRef,
                    restartMessageBufferRef,
                    errorMessageBufferRef,
                    proxy
                  )
                )
          }.flatten
      }
    }
  }
}

final case class TrackingActor[F[+_]: Parallel: Concurrent: Temporal: Console, Request, Response](
    initCountRef: Ref[F, Int],
    preStartCountRef: Ref[F, Int],
    postStopCountRef: Ref[F, Int],
    preRestartCountRef: Ref[F, Int],
    postRestartCountRef: Ref[F, Int],
    preSuspendCountRef: Ref[F, Int],
    preResumeCountRef: Ref[F, Int],
    messageBufferRef: Ref[F, List[Any]],
    restartMessageBufferRef: Ref[F, List[(Option[Throwable], Option[Any])]],
    errorMessageBufferRef: Ref[F, List[(Throwable, Option[Any])]],
    proxy: ReplyingActor[F, Request, Response]
) extends ReplyingActor[F, Request, Response]
    with ActorConfig {

  override val receive: ReplyingReceive[F, Request, Response] = { case m =>
    messageBufferRef.update(_ ++ List(m)) >> Console[F].println(m) >> proxy.receive(m)
  }

  override def supervisorStrategy: SupervisionStrategy[F] = proxy.supervisorStrategy

  override def onError(reason: Throwable, message: Option[Any]): F[Unit] =
    errorMessageBufferRef.update(_ ++ List((reason, message))) >>
      proxy.onError(reason, message)

  override def init: F[Unit] =
    initCountRef.update(_ + 1) >>
      Unsafe
        .setActorContext(
          Unsafe.setActorSelf[F, Request, Response](proxy, self),
          context
        )
        .pure[F]
        .void

  override def preStart: F[Unit] =
    // This is needed because we are interfering with the actor spec ourselves.
    preStartCountRef.update(_ + 1) >> proxy.preStart

  override def postStop: F[Unit] =
    postStopCountRef.update(_ + 1) >> proxy.postStop

  override def postRestart(reason: Option[Throwable]): F[Unit] =
    postRestartCountRef.update(_ + 1) >> proxy.postRestart(reason)

  override def preRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    restartMessageBufferRef.update(_ ++ List(reason -> message)) >> preRestartCountRef.update(
      _ + 1
    ) >> proxy.preRestart(reason, message)

  override def preSuspend(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    preSuspendCountRef.update(_ + 1) >> proxy.preSuspend(reason, message)

  override def preResume: F[Unit] =
    preResumeCountRef.update(_ + 1) >> proxy.preResume

  // Note: This is an internal method and should be used sparingly and only in testing.
  // Tracked actors are special kind of actors in which we do not want to clear the actor cell
  // when the actor is terminated so that we still have access to statistics.
  override def clearActor: Boolean = false
}
