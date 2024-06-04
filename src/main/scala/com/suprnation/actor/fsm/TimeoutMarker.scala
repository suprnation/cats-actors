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

import com.suprnation.actor.ActorRef

/** INTERNAL API
  */
case class TimeoutMarker[F[+_]](generation: Long, originalSender: Option[ActorRef[F]])

/** INTERNAL API */
private[fsm] sealed trait TimerMode {
  def repeat: Boolean
}

/** INTERNAL API */
private[fsm] case object FixedRateMode extends TimerMode {
  override def repeat: Boolean = true
}

/** INTERNAL API */
private[fsm] case object FixedDelayMode extends TimerMode {
  override def repeat: Boolean = true
}

/** INTERNAL API */
private[fsm] case object SingleMode extends TimerMode {
  override def repeat: Boolean = false
}
