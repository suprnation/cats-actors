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

package com.suprnation.actor.dispatch

import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor._

sealed trait SystemMessage[F[+_]] extends PossiblyHarmful

trait StashWhenWaitingForChildren

trait StashWhenFailed

object SystemMessage {
  final case class Create[F[+_]](failure: Option[ActorInitializationException[F]])
      extends SystemMessage[F]

  final case class Recreate[F[+_]](cause: Option[Throwable])
      extends SystemMessage[F]
      with StashWhenWaitingForChildren

  final case class Suspend[F[+_]](causeByFailure: Option[Throwable])
      extends SystemMessage[F]
      with StashWhenWaitingForChildren

  final case class Resume[F[+_]](causeByFailure: Option[Throwable])
      extends SystemMessage[F]
      with StashWhenWaitingForChildren

  final case class Terminate[F[+_]]() extends SystemMessage[F] with DeadLetterSuppression

  final case class Supervise[F[+_]](child: NoSendActorRef[F]) extends SystemMessage[F]

  final case class Watch[F[+_], Request](
      watchee: NoSendActorRef[F],
      watcher: NoSendActorRef[F],
      onTerminated: Request
  ) extends SystemMessage[F]

  final case class UnWatch[F[+_]](watchee: NoSendActorRef[F], watcher: NoSendActorRef[F])
      extends SystemMessage[F]

  case class NoMessage[F[+_]]() extends SystemMessage[F]

  final case class Failed[F[+_]](child: NoSendActorRef[F], cause: Throwable, uid: Int)
      extends SystemMessage[F]
      with StashWhenFailed
      with StashWhenWaitingForChildren

  final case class DeathWatchNotification[F[+_]](
      actor: NoSendActorRef[F],
      existenceConfirmed: Boolean,
      addressTerminated: Boolean
  ) extends SystemMessage[F]
      with DeadLetterSuppression

  final case class Ping[F[+_]]() extends SystemMessage[F]
}
