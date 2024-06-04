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

import cats.effect._
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

case class Scheduler[F[+_]: Concurrent](scheduled: Ref[F, Int]) {

  def isIdle: F[Boolean] =
    scheduled.get.map(_ > 0)

  /** Schedules a Runnable to be run once with a delay, i.e. a time period that has to pass before the runnable is executed.
    *
    * @param initialDelay
    *   the initial delay
    * @param fa
    *   the effect to run
    */
  def scheduleOnce[A](initialDelay: FiniteDuration)(fa: => F[A])(implicit
      temporalEvidence: Temporal[F]
  ): F[A] =
    scheduled.update(_ + 1) >>
      temporalEvidence.sleep(initialDelay) >> fa <*
      scheduled.update(_ - 1)

  /** The schedule once here will not wait for the result and will run the result in a fibre.
    *
    * This method is handy when you want to schedule some task in the pre-start.
    *
    * @param initialDelay
    *   the initial delay
    * @param fa
    *   the effect to run
    */
  def scheduleOnce_[A](initialDelay: FiniteDuration)(fa: => F[A])(implicit
      temporalEvidence: Temporal[F]
  ): F[Fiber[F, Throwable, A]] =
    scheduled.update(_ + 1) >>
      temporalEvidence.start(temporalEvidence.sleep(initialDelay) >> fa) <*
      scheduled.update(_ - 1)
}
