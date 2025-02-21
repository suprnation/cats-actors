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

package com.suprnation.actor.dungeon

import cats.effect.Ref
import cats.effect.kernel.Concurrent
import cats.implicits._
import cats.effect.implicits._
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.dispatch.SystemMessage.{DeathWatchNotification, UnWatch, Watch}
import com.suprnation.actor.dungeon.DeathWatch.DeathWatchContext
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.{Debug, Warning}
import com.suprnation.actor.{Envelope, InternalActorRef, Terminated}

import scala.collection.immutable.HashSet

object DeathWatch {
  def createContext[F[+_]: Concurrent, Request]: F[DeathWatchContext[F, Request]] = for {
    _watchingRef <- Ref.of[F, Map[NoSendActorRef[F], Request]](
      Map.empty[NoSendActorRef[F], Request]
    )
    _watchedByRef <- Ref.of[F, Set[NoSendActorRef[F]]](HashSet.empty[NoSendActorRef[F]])
  } yield DeathWatchContext[F, Request](
    watchingRef = _watchingRef,
    watchedByRef = _watchedByRef
  )

  case class DeathWatchContext[F[+_], Request](
      watchingRef: Ref[F, Map[NoSendActorRef[F], Request]],
      watchedByRef: Ref[F, Set[NoSendActorRef[F]]]
  )
}

trait DeathWatch[F[+_], Request, Response] {
  self: ActorCell[F, Request, Response] =>

  val deathWatchContext: DeathWatchContext[F, Request]

  def isWatching(ref: NoSendActorRef[F]): F[Boolean] =
    deathWatchContext.watchingRef.get.map(_ contains ref)

  def receivedTerminated(t: Terminated[F, Request]): F[Unit] =
    (removeChildAndGetStateChange(t.actor) >> receiveMessage(Envelope(t.userMessage))).void

  protected def watchedActorTerminated(
      actor: NoSendActorRef[F],
      existenceConfirmed: Boolean,
      addressTerminated: Boolean
  ): F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      _ <- watching.get(actor) match {
        case None => asyncF.unit
        case Some(message) =>
          deathWatchContext.watchingRef.update(watching => watching - actor) >>
            asyncF.ifM(isTerminated)(
              asyncF.unit,
              sendMessage(Envelope(Terminated(actor, message), actor), None)
            )

      }
      children <- childrenRefs.get
      _ <- children.getByRef(actor).fold(asyncF.unit)(_ => handleChildTerminated(actor))
    } yield ()

  protected def tellWatcherWeDied(): F[Unit] = {
    def sendTerminated(watcher: NoSendActorRef[F]): F[Unit] =
      if (watcher != parent) {
        watcher
          .asInstanceOf[InternalActorRef[F, Nothing, Any]]
          .sendSystemMessage(
            Envelope.system(
              DeathWatchNotification(
                self.self,
                existenceConfirmed = true,
                addressTerminated = false
              )
            )(self.self)
          )
      } else asyncF.unit

    (for {
      watchedBy <- deathWatchContext.watchedByRef.get
      _ <- watchedBy.toList.parTraverse_(sendTerminated)
    } yield ()).guarantee(
      deathWatchContext.watchedByRef.update(watchedBy => HashSet.empty[NoSendActorRef[F]])
    )
  }

  protected def unwatchWatchedActor: F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      _ <-
        if (watching.nonEmpty) {
          watching.toList
            .parTraverse_ {
              case (watchee: InternalActorRef[F, ?, ?], _) =>
                watchee.sendSystemMessage(
                  Envelope.systemNoSender(
                    UnWatch(watchee, self.self)
                  )
                )
              case (watchee, _) =>
                Concurrent[F].raiseError(
                  new IllegalStateException(
                    s"Expected InternalActorRef, but got [${watchee.getClass.getName}]"
                  )
                )
            }
            .guarantee(
              deathWatchContext.watchingRef.set(Map.empty)
            )

        } else asyncF.unit
    } yield ()

  protected def addWatcher(
      watchee: NoSendActorRef[F],
      watcher: NoSendActorRef[F],
      onTerminated: Request
  ): F[Unit] =
    for {
      watcheeSelf <- Concurrent[F].pure(watchee == (self.self))
      watcherSelf <- Concurrent[F].pure(watcher == (self.self))

      _ <-
        if (watcheeSelf && !watcherSelf) {
          deathWatchContext.watchedByRef.get >>= (watchedBy =>
            if (!watchedBy.contains(watcher)) {
              deathWatchContext.watchedByRef.update(watchedBy => watchedBy + watcher) >>
                asyncF.whenA(system.settings.DebugLifecycle)(
                  publish(Debug(self.self.path.toString, _, s"now watched by $watcher"))
                )
            } else asyncF.unit
          )
        } else if (!watcheeSelf && watcherSelf) {
          watch(watchee, onTerminated)
        } else {
          publish(
            Warning(
              self.self.path.toString,
              _,
              "BUG: illegal Watch(%s, %s) for %s".format(watchee, watcher, self.self)
            )
          )
        }
    } yield ()

  def watch(subject: NoSendActorRef[F], onTerminated: Request): F[NoSendActorRef[F]] =
    subject match {
      case a: InternalActorRef[F, Nothing, Any] =>
        for {
          selfReference <- self.actor.map(_.self)
          _ <-
            if (a != selfReference) {
              for {
                watching <- deathWatchContext.watchingRef.get
                _ <-
                  if (!watching.contains(a)) {
                    a.sendSystemMessage(
                      Envelope.systemNoSender(
                        Watch(a, selfReference, onTerminated)
                      )
                    ) >> updateWatching(a, onTerminated)
                  } else checkWatchingSame(a, onTerminated)
              } yield ()
            } else {
              asyncF.pure(a)
            }
        } yield a

      case unexpected =>
        asyncF.raiseError(
          new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
        )
    }

  protected def updateWatching(
      ref: InternalActorRef[F, Nothing, Any],
      onTerminated: Request
  ): F[Unit] =
    deathWatchContext.watchingRef.update(watching => watching.updated(ref, onTerminated))

  protected def checkWatchingSame(
      ref: InternalActorRef[F, Nothing, Any],
      newMessage: Request
  ): F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      previous = watching.get(ref)
      _ <-
        if (previous != newMessage) {
          asyncF.raiseError(
            new IllegalStateException(
              s"Watch($self, $ref) termination message was not overwritten from [$previous] to [$newMessage]. " +
                s"If this was intended, unwatch first before using `watch` / `watchWith` with another message."
            )
          )
        } else asyncF.unit
    } yield ()

  protected def removeWatcher(watchee: NoSendActorRef[F], watcher: NoSendActorRef[F]): F[Unit] =
    for {
      watcheeSelf <- Concurrent[F].pure(watchee == self.self)
      watcherSelf <- Concurrent[F].pure(watcher == self.self)

      _ <-
        if (watcheeSelf && !watcherSelf) {
          deathWatchContext.watchedByRef.get >>= (watchedBy =>
            if (watchedBy.contains(watcher))
              deathWatchContext.watchedByRef.update(watchedBy => watchedBy - watcher) >>
                system.settings.DebugLifecycle
                  .pure[F]
                  .ifM(
                    publish(Debug(self.self.path.toString, _, s"no longer watched by $watcher")),
                    asyncF.unit
                  )
            else asyncF.unit
          )
        } else if (!watcheeSelf && watcherSelf) {
          unwatch(watchee)
        } else {
          publish(
            Warning(
              self.self.path.toString,
              _,
              "BUG: illegal Unwatch(%s,%s) for %s".format(watchee, watcher, self)
            )
          )
        }

    } yield ()

  def unwatch(subject: NoSendActorRef[F]): F[NoSendActorRef[F]] =
    subject match {
      case a: InternalActorRef[F, Nothing, Any] =>
        for {
          watching <- deathWatchContext.watchingRef.get
          selfReference <- self.actor.map(_.self)
          _ <-
            if (a != selfReference && watching.contains(a)) {
              a.sendSystemMessage(
                Envelope.systemNoSender(UnWatch(a, selfReference))
              ) >>
                deathWatchContext.watchingRef
                  .update(watching => watching - a)
                  .as(Some(a))

            } else asyncF.unit

        } yield a
      case unexpected =>
        asyncF.raiseError(
          new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
        )
    }

}
