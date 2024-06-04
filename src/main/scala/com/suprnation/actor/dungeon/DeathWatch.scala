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

import cats.effect.kernel.Concurrent
import cats.effect.{Async, Ref}
import cats.syntax.all._
import cats.effect.syntax.all._
import com.suprnation.actor.dispatch.SystemMessage.{DeathWatchNotification, UnWatch, Watch}
import com.suprnation.actor.dungeon.DeathWatch.DeathWatchContext
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.{Debug, Warning}
import com.suprnation.actor.{ActorRef, Envelope, InternalActorRef, Terminated}

import scala.collection.immutable.HashSet

object DeathWatch {
  def createContext[F[+_]: Concurrent]: F[DeathWatchContext[F]] = for {
    _watchingRef <- Ref.of[F, Map[ActorRef[F], Option[Any]]](Map.empty[ActorRef[F], Option[Any]])
    _watchedByRef <- Ref.of[F, Set[ActorRef[F]]](HashSet.empty[ActorRef[F]])
  } yield DeathWatchContext[F](
    watchingRef = _watchingRef,
    watchedByRef = _watchedByRef
  )

  case class DeathWatchContext[F[+_]](
      watchingRef: Ref[F, Map[ActorRef[F], Option[Any]]],
      watchedByRef: Ref[F, Set[ActorRef[F]]]
  )
}

trait DeathWatch[F[+_]] {
  self: ActorCell[F] =>

  val deathWatchContext: DeathWatchContext[F]
  implicit val concurrentF: Concurrent[F]
  implicit val asyncF: Async[F]

  def isWatching(ref: ActorRef[F]): F[Boolean] =
    deathWatchContext.watchingRef.get.map(_ contains ref)

  def receivedTerminated(t: Terminated[F]): F[Unit] =
    receiveMessage(Envelope(t)) >> removeChildAndGetStateChange(t.actor).void

  protected def watchedActorTerminated(
      actor: ActorRef[F],
      existenceConfirmed: Boolean,
      addressTerminated: Boolean
  ): F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      _ <- watching.get(actor) match {
        case None => concurrentF.unit
        case Some(optionalMessage) =>
          deathWatchContext.watchingRef.update(watching => watching - actor) >>
            asyncF.ifM(isTerminated)(
              asyncF.unit,
              sendMessage(Envelope(Terminated(actor), actor), None)
            )

      }
      children <- childrenRefs.get
      _ <- children.getByRef(actor).fold(concurrentF.unit)(_ => handleChildTerminated(actor))
    } yield ()

  protected def tellWatcherWeDied(): F[Unit] = {
    def sendTerminated(watcher: ActorRef[F]): F[Unit] =
      if (watcher != parent) {
        watcher
          .asInstanceOf[InternalActorRef[F]]
          .sendSystemMessage(
            Envelope.system(
              DeathWatchNotification(
                self.self,
                existenceConfirmed = true,
                addressTerminated = false
              )
            )(self.self)
          )
      } else concurrentF.unit

    (for {
      watchedBy <- deathWatchContext.watchedByRef.get
      _ <- watchedBy.toList.parTraverse_(sendTerminated)
    } yield ()).guarantee(
      deathWatchContext.watchedByRef.update(watchedBy => HashSet.empty[ActorRef[F]])
    )
  }

  protected def unwatchWatchedActor: F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      _ <-
        if (watching.nonEmpty) {
          watching.toList
            .parTraverse_ {
              case (watchee: InternalActorRef[F], _) =>
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

        } else concurrentF.unit
    } yield ()

  protected def addWatcher(watchee: ActorRef[F], watcher: ActorRef[F]): F[Unit] =
    for {
      watcheeSelf <- Concurrent[F].pure(watchee == (self.self))
      watcherSelf <- Concurrent[F].pure(watcher == (self.self))

      _ <-
        if (watcheeSelf && !watcherSelf) {
          deathWatchContext.watchedByRef.get >>= (watchedBy =>
            if (!watchedBy.contains(watcher)) {
              deathWatchContext.watchedByRef.update(watchedBy => watchedBy + watcher) >>
                system.settings.DebugLifecycle
                  .pure[F]
                  .ifM(
                    publish(Debug(self.self.path.toString, _, s"now watched by $watcher")),
                    concurrentF.unit
                  )
            } else concurrentF.unit
          )
        } else if (!watcheeSelf && watcherSelf) {
          watch(watchee)
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

  def watch(subject: ActorRef[F]): F[ActorRef[F]] =
    subject match {
      case a: InternalActorRef[F] =>
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
                        Watch(a, selfReference.asInstanceOf[InternalActorRef[F]])
                      )
                    ) >> updateWatching(a, None)
                  } else checkWatchingSame(a, None)
              } yield ()
            } else {
              concurrentF.pure(a)
            }
        } yield a

      case unexpected =>
        concurrentF.raiseError(
          new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
        )
    }

  protected def updateWatching(ref: InternalActorRef[F], newMessage: Option[Any]): F[Unit] =
    deathWatchContext.watchingRef.update(watching => watching.updated(ref, newMessage))

  protected def checkWatchingSame(ref: InternalActorRef[F], newMessage: Option[Any]): F[Unit] =
    for {
      watching <- deathWatchContext.watchingRef.get
      previous = watching.get(ref)
      _ <-
        if (previous != newMessage) {
          concurrentF.raiseError(
            new IllegalStateException(
              s"Watch($self, $ref) termination message was not overwritten from [$previous] to [$newMessage]. " +
                s"If this was intended, unwatch first before using `watch` / `watchWith` with another message."
            )
          )
        } else concurrentF.unit
    } yield ()

  protected def removeWatcher(watchee: ActorRef[F], watcher: ActorRef[F]): F[Unit] =
    for {
      watcheeSelf <- Concurrent[F].pure(watchee == (self.self))
      watcherSelf <- Concurrent[F].pure(watcher == (self.self))

      _ <-
        if (watcheeSelf && !watcherSelf) {
          deathWatchContext.watchedByRef.get >>= (watchedBy =>
            if (watchedBy.contains(watcher))
              deathWatchContext.watchedByRef.update(watchedBy => watchedBy - watcher) >>
                system.settings.DebugLifecycle
                  .pure[F]
                  .ifM(
                    publish(Debug(self.self.path.toString, _, s"no longer watched by $watcher")),
                    concurrentF.unit
                  )
            else concurrentF.unit
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

  def unwatch(subject: ActorRef[F]): F[ActorRef[F]] =
    subject match {
      case a: InternalActorRef[F] =>
        for {
          watching <- deathWatchContext.watchingRef.get
          selfReference <- self.actor.map(_.self)
          _ <-
            if (a != selfReference && watching.contains(a)) {
              a.sendSystemMessage(
                Envelope.systemNoSender(UnWatch(a, selfReference.asInstanceOf[InternalActorRef[F]]))
              ) >>
                deathWatchContext.watchingRef
                  .update(watching => watching - a)
                  .as(Some(a.asInstanceOf[ActorRef[F]]))

            } else concurrentF.unit

        } yield a
      case unexpected =>
        concurrentF.raiseError(
          new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
        )
    }

}
