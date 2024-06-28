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

import com.suprnation.actor.ActorRef.NoSendActorRef

import java.util.concurrent.TimeUnit

sealed trait ChildStats

case object ChildNameReserved extends ChildStats

/** ChildRestartStats is the statistics kept by every parent Actor for every child Actor and used for SupervisionStrategies to know how to deal with problems that occur for the children.
  */
final case class ChildRestartStats[F[+_]](
    child: NoSendActorRef[F],
    var maxNrOfRetriesCount: Int = 0,
    var restartTimeWindowStartNanos: Long = 0L
) extends ChildStats {

  def uid: Int = child.path.uid

  // FIXME How about making ChildRestartStats immutable and then move these methods into the actual supervisor strategies?
  def requestRestartPermission(retriesWindow: (Option[Int], Option[Int])): Boolean =
    retriesWindow match {
      case (Some(retries), _) if retries < 1 => false
      case (Some(retries), None) => maxNrOfRetriesCount += 1; maxNrOfRetriesCount <= retries
      case (x, Some(window))     => retriesInWindowOkay(if (x.isDefined) x.get else 1, window)
      case (None, _)             => true
    }

  private def retriesInWindowOkay(retries: Int, window: Int): Boolean = {
    /*
     * Simple window algorithm: window is kept open for a certain time
     * after a restart and if enough restarts happen during this time, it
     * denies. Otherwise window closes and the scheme starts over.
     */
    val retriesDone = maxNrOfRetriesCount + 1
    val now = System.nanoTime
    val windowStart =
      if (restartTimeWindowStartNanos == 0) {
        restartTimeWindowStartNanos = now
        now
      } else restartTimeWindowStartNanos
    val insideWindow = (now - windowStart) <= TimeUnit.MILLISECONDS.toNanos(window)
    if (insideWindow) {
      maxNrOfRetriesCount = retriesDone
      retriesDone <= retries
    } else {
      maxNrOfRetriesCount = 1
      restartTimeWindowStartNanos = now
      true
    }
  }
}
