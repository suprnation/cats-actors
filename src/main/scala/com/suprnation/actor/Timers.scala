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

import cats.effect.{Async, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.dungeon.{TimerScheduler, TimerSchedulerImpl}
import com.suprnation.actor.dungeon.TimerSchedulerImpl.{StoredTimer, Timer}

trait Timers[F[+_], Request, Key] {
  _: Actor[F, Request] =>

  // to be removed with scala 3
  implicit def asyncEvidence: Async[F]

  protected val timerRef: Ref[F, Map[Key, StoredTimer[F]]]
  protected val timerGen: Ref[F, Int]

  private lazy val _timers = new TimerSchedulerImpl[F, Request, Key](timerGen, timerRef, context)
  final def timers: TimerScheduler[F, Request, Key] = _timers

  override def aroundPreRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    timers.cancelAll >> preRestart(reason, message)

  override def aroundPostStop(): F[Unit] =
    timers.cancelAll >> postStop

  override def aroundReceive(receive: Receive[F, Request], msg: Any): F[Any] =
    msg match {
      case t: Timer[F, Request, Key] => _timers.interceptTimerMsg(t).ifM(receive(t.msg), unhandled(t.msg))
      case _ => receive.applyOrElse(msg.asInstanceOf[Request], unhandled)
    }
}
