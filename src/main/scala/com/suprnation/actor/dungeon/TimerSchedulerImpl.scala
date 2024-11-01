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

import cats.effect.{Async, Fiber, Ref}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.dungeon.TimerSchedulerImpl.TimerMode.{FixedDelay, Single}
import com.suprnation.actor.dungeon.TimerSchedulerImpl._
import com.suprnation.actor.{ActorContext, Scheduler, SystemCommand}

import scala.concurrent.duration.FiniteDuration

sealed trait TimerScheduler[F[_], Request, Key] {

  /** Start a timer that will send `msg` once to the `self` actor after
    * the given `timeout`.
    */
  def startSingleTimer(key: Key, msg: Request, delay: FiniteDuration): F[Unit]

  /** Scala API: Schedules a message to be sent repeatedly to the `self` actor with a
    * fixed `delay` between messages.
    */
  def startTimerWithFixedDelay(key: Key, msg: Request, delay: FiniteDuration): F[Unit]

  /** Check if a timer with a given `key` is active.
    */
  def isTimerActive(key: Key): F[Boolean]

  /** Cancel a timer with a given `key`.
    * If canceling a timer that was already canceled, or key never was used to start a timer
    * this operation will do nothing.
    */
  def cancel(key: Key): F[Unit]

  /** Cancel all timers.
    */
  def cancelAll: F[Unit]

}

/** INTERNAL API */
private[actor] object TimerSchedulerImpl {
  sealed trait TimerMode {
    def repeat: Boolean
  }

  object TimerMode {
    case object FixedDelay extends TimerMode {
      override def repeat: Boolean = true
    }

    case object Single extends TimerMode {
      override def repeat: Boolean = false
    }
  }

  final case class Timer[F[+_]: Async, Request, Key](
      key: Key,
      msg: Request,
      mode: TimerMode,
      generation: Int,
      owner: ActorRef[F, Request]
  )(scheduler: Scheduler[F])
      extends SystemCommand {

    def schedule(
        actor: ActorRef[F, Request],
        timeout: FiniteDuration
    ): F[Fiber[F, Throwable, Unit]] =
      mode match {
        case Single     => scheduler.scheduleOnce_(timeout)(actor !* this)
        case FixedDelay => scheduler.scheduleWithFixedDelay(timeout, timeout)(actor !* this)
      }
  }

  final case class StoredTimer[F[+_]](
      generation: Int,
      fiber: Fiber[F, Throwable, Unit]
  ) {
    def cancel: F[Unit] = fiber.cancel
  }
}

private[actor] class TimerSchedulerImpl[F[+_]: Async, Request, Key](
    private val timerGenRef: Ref[F, Int],
    private val timersRef: Ref[F, Map[Key, StoredTimer[F]]],
    private val context: ActorContext[F, Request, Any]
) extends TimerScheduler[F, Request, Key] {

  private lazy val self: ActorRef[F, Request] = context.self

  override def startSingleTimer(key: Key, msg: Request, delay: FiniteDuration): F[Unit] =
    startTimer(key, msg, delay, Single)

  def startTimerWithFixedDelay(key: Key, msg: Request, delay: FiniteDuration): F[Unit] =
    startTimer(key, msg, delay, FixedDelay)

  private def startTimer(
      key: Key,
      msg: Request,
      timeout: FiniteDuration,
      mode: TimerMode
  ): F[Unit] =
    for {
      gen <- timerGenRef.getAndUpdate(_ + 1)
      timer = Timer(key, msg, mode, gen, self)(context.system.scheduler)
      fiber <- timer.schedule(self, timeout)
      _ <-
        timersRef.flatModify { timers =>
          (
            timers + (key -> StoredTimer(gen, fiber)),
            timers.get(key).map(_.cancel).getOrElse(Async[F].unit)
          )
        }
    } yield ()

  def isTimerActive(key: Key): F[Boolean] = timersRef.get.map(_.contains(key))

  def cancel(key: Key): F[Unit] =
    timersRef.flatModify { timers =>
      (timers - key, timers.get(key).map(_.cancel).getOrElse(Async[F].unit))
    }

  def cancelAll: F[Unit] =
    timersRef.flatModify { timers =>
      (Map.empty, timers.view.values.toList.traverse_(_.cancel))
    }

  def interceptTimerMsg(t: Timer[F, Request, Key]): F[Boolean] =
    if (!(t.owner eq self)) Async[F].pure(false)
    else
      timersRef.get
        .map(_.get(t.key).exists(_.generation == t.generation))
        .ifM(
          removeSingleTimer(t).as(true),
          Async[F].pure(false)
        )

  private def removeSingleTimer(t: Timer[F, Request, Key]): F[Unit] =
    if (!t.mode.repeat) timersRef.update(_ - t.key)
    else Async[F].unit
}
