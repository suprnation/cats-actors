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

package com.suprnation.actor.fsm

import cats.effect.{Async, Fiber, Temporal}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.{Scheduler, SystemCommand}

import scala.concurrent.duration.FiniteDuration

/** INTERNAL API
  */
case class TimeoutMarker[F[+_], Request](
    generation: Long,
    originalSender: Option[NoSendActorRef[F]],
    request: Request
) extends SystemCommand

/** INTERNAL API */
private[fsm] sealed trait TimerMode {
  def repeat: Boolean
}

/** INTERNAL API */
private[fsm] case object FixedDelayMode extends TimerMode {
  override def repeat: Boolean = true
}

/** INTERNAL API */
private[fsm] case object SingleMode extends TimerMode {
  override def repeat: Boolean = false
}

private[fsm] final case class Timer[F[+_]: Async: Temporal, Request](
    name: String,
    msg: Request,
    mode: TimerMode,
    generation: Int,
    owner: ActorRef[F, Request]
)(scheduler: Scheduler[F]) {

  def schedule(
      actor: ActorRef[F, Any],
      timeout: FiniteDuration
  ): F[Fiber[F, Throwable, Unit]] =
    mode match {
      case SingleMode => scheduler.scheduleOnce_(timeout)(actor ! this)
      case FixedDelayMode =>
        scheduler.scheduleWithFixedDelay(timeout, timeout)(actor ! this)
    }
}

private[fsm] final case class StoredTimer[F[+_]](
    generation: Int,
    fiber: Fiber[F, Throwable, Unit]
) {
  def cancel: F[Unit] = fiber.cancel
}
