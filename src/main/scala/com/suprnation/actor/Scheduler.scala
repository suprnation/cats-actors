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
import cats.effect.implicits._

import scala.concurrent.duration.FiniteDuration

case class Scheduler[F[+_]: Async](scheduled: Ref[F, Int]) {
  def isIdle: F[Boolean] =
    scheduled.get.map(_ == 0)

  /** Schedules a Runnable to be run once with a delay, i.e. a time period that has to pass before the runnable is executed.
    *
    * @param initialDelay
    *   the initial delay
    * @param fa
    *   the effect to run
    */
  def scheduleOnce[A](initialDelay: FiniteDuration)(fa: => F[A]): F[A] =
    scheduled.update(_ + 1) >> 
      fa.delayBy(initialDelay)
        .attemptTap(_ => scheduled.update(_ - 1))
        .onCancel(scheduled.update(_ - 1))

  /** The schedule once here will not wait for the result and will run the result in a fibre.
    *
    * This method is handy when you want to schedule some task in the pre-start.
    *
    * @param initialDelay
    *   the initial delay
    * @param fa
    *   the effect to run
    */
  def scheduleOnce_[A](initialDelay: FiniteDuration)(fa: => F[A]): F[Fiber[F, Throwable, A]] =
    scheduled.update(_ + 1) >> 
      fa.delayBy(initialDelay)
        .attemptTap(_ => scheduled.update(_ - 1))
        .onCancel(scheduled.update(_ - 1))
        .start


 /** Schedules a `Runnable` to be run repeatedly with an initial delay and
    * a fixed `delay` between subsequent executions. E.g. if you would like the function to
    * be run after 2 seconds and thereafter every 100ms you would set delay to `Duration.ofSeconds(2)`,
    * and interval to `Duration.ofMillis(100)`.
    *
    * It will not compensate the delay between tasks if the execution takes a long time or if
    * scheduling is delayed longer than specified for some reason. The delay between subsequent
    * execution will always be (at least) the given `delay`.
    *
    * In the long run, the frequency of tasks will generally be slightly lower than
    * the reciprocal of the specified `delay`.
    *
    * If the `Runnable` throws an exception the repeated scheduling is aborted,
    * i.e. the function will not be invoked any more.
    *
    * @param initialDelay
    *   the initial delay
    * @param fa
    *   the effect to run
    */
  def scheduleWithFixedDelay[A](initialDelay: FiniteDuration, delay: FiniteDuration)(fa: => F[A]): F[Fiber[F, Throwable, Unit]] =
    scheduled.update(_ + 1) >>
      fa.andWait(delay).foreverM
        .delayBy(initialDelay)
        .attemptTap((_: Either[Throwable, Unit]) => scheduled.update(_ - 1))
        .onCancel(scheduled.update(_ - 1))
        .start
}
