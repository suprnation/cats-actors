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
import cats.implicits.{catsSyntaxFlatMapOps, catsSyntaxIfM, toFlatMapOps}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.dungeon.{TimerScheduler, TimerSchedulerImpl}
import com.suprnation.actor.dungeon.TimerSchedulerImpl.{StoredTimer, Timer}

import scala.util.Try

trait Timers[F[+_], Request, Key] extends Actor[F, Request] {

  implicit def asyncEvidence: Async[F]

  protected val timerGenRef: Ref[F, Int]
  protected val timersRef: Ref[F, Map[Key, StoredTimer[F]]]

  private lazy val _timers =
    new TimerSchedulerImpl[F, Request, Key](timerGenRef, timersRef, context)
  final def timers: TimerScheduler[F, Request, Key] = _timers

  override def aroundPreRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    timers.cancelAll >> super.aroundPreRestart(reason, message)

  override def aroundPostStop(): F[Unit] =
    timers.cancelAll >> super.aroundPostStop()

  override def aroundReceive(receive: Receive[F, Request], msg: Any): F[Any] =
    msg match {
      case timer @ Timer(_, _, _, _, _) =>
        Async[F]
          .fromTry(Try(timer.asInstanceOf[Timer[F, Request, Key]]))
          .flatMap(t =>
            _timers
              .interceptTimerMsg(t)
              .ifM(
                super.aroundReceive(receive, t.msg),
                unhandled(t.msg)
              )
          )

      case _ => super.aroundReceive(receive, msg)
    }
}
