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
import com.suprnation.actor.fsm.State.SilentState

import scala.concurrent.duration.FiniteDuration

object State {
  def apply[S, D](
      stateName: S,
      stateData: D,
      timeout: Option[FiniteDuration] = None,
      stopReason: Option[Reason] = None,
      replies: List[Any] = Nil
  ): State[S, D] =
    new State(stateName, stateData, timeout, stopReason, replies)

  def unapply[S, D](
      state: State[S, D]
  ): Option[(S, D, Option[FiniteDuration], Option[Reason], List[Any])] =
    Some((state.stateName, state.stateData, state.timeout, state.stopReason, state.replies))

  case class StateTimeoutWithSender[F[+_]](original: Option[ActorRef[F]])

  class SilentState[S, D](
      stateName: S,
      stateData: D,
      timeout: Option[FiniteDuration],
      stopReason: Option[Reason],
      replies: List[Any]
  ) extends State[S, D](stateName, stateData, timeout, stopReason, replies) {

    override def notifies: Boolean = false

    override def copy(
        stateName: S = stateName,
        stateData: D = stateData,
        timeout: Option[FiniteDuration] = timeout,
        stopReason: Option[Reason] = stopReason,
        replies: List[Any] = replies
    ): State[S, D] =
      new SilentState(stateName, stateData, timeout, stopReason, replies)

  }

  case object StateTimeout
}

class State[S, D](
    val stateName: S,
    val stateData: D,
    val timeout: Option[FiniteDuration] = None,
    val stopReason: Option[Reason] = None,
    val replies: List[Any] = Nil
) {

  def notifies: Boolean = true

  def replying(replyValue: Any): State[S, D] =
    copy(replies = replyValue :: replies)

  def using(nextStateData: D): State[S, D] =
    copy(stateData = nextStateData)

  def withStopReason(reason: Reason): State[S, D] =
    copy(stopReason = Some(reason))

  def forMax(duration: Option[FiniteDuration]): State[S, D] =
    copy(timeout = duration)

  // defined here to be able to override it in SilentState
  def copy(
      stateName: S = stateName,
      stateData: D = stateData,
      timeout: Option[FiniteDuration] = timeout,
      stopReason: Option[Reason] = stopReason,
      replies: List[Any] = replies
  ): State[S, D] =
    new State(stateName, stateData, timeout, stopReason, replies)

  def withNotification(notifies: Boolean): State[S, D] =
    if (notifies)
      State(stateName, stateData, timeout, stopReason, replies)
    else
      new SilentState(stateName, stateData, timeout, stopReason, replies)
}
