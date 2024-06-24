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

import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.fsm.State.SilentState
import com.suprnation.typelevel.fsm.syntax.Timeout

import scala.concurrent.duration.FiniteDuration

object State {
  def apply[S, D, Request, Response](
      stateName: S,
      stateData: D,
      timeout: Timeout[Request] = None,
      stopReason: Option[Reason] = None,
      replies: List[Response] = Nil
  ): State[S, D, Request, Response] =
    new State(stateName, stateData, timeout, stopReason, replies)

  def unapply[S, D, Request, Response](
      state: State[S, D, Request, Response]
  ): Option[(S, D, Timeout[Request], Option[Reason], List[Any])] =
    Some((state.stateName, state.stateData, state.timeout, state.stopReason, state.replies))

  case class StateTimeoutWithSender[F[+_], Request](original: Option[NoSendActorRef[F]], msg: Request)

  class SilentState[S, D, Request, Response](
      stateName: S,
      stateData: D,
      timeout: Timeout[Request],
      stopReason: Option[Reason],
      replies: List[Response]
  ) extends State[S, D, Request, Response](stateName, stateData, timeout, stopReason, replies) {

    override def notifies: Boolean = false

    override def copy(
        stateName: S = stateName,
        stateData: D = stateData,
        timeout: Timeout[Request] = timeout,
        stopReason: Option[Reason] = stopReason,
        replies: List[Response] = replies
    ): State[S, D, Request, Response] =
      new SilentState(stateName, stateData, timeout, stopReason, replies)

  }

  case object StateTimeout
}

class State[S, D, Request, Response](
    val stateName: S,
    val stateData: D,
    val timeout: Timeout[Request] = None,
    val stopReason: Option[Reason] = None,
    val replies: List[Response] = Nil
) {

  def notifies: Boolean = true

  def replying(replyValue: Response): State[S, D, Request, Response] =
    copy(replies = replyValue :: replies)

  def using(nextStateData: D): State[S, D, Request, Response] =
    copy(stateData = nextStateData)

  def withStopReason(reason: Reason): State[S, D, Request, Response] =
    copy(stopReason = Some(reason))

  def forMax(duration: Option[(FiniteDuration, Request)]): State[S, D, Request, Response] =
    copy(timeout = duration)

  // defined here to be able to override it in SilentState
  def copy(
      stateName: S = stateName,
      stateData: D = stateData,
      timeout: Timeout[Request] = timeout,
      stopReason: Option[Reason] = stopReason,
      replies: List[Response] = replies
  ): State[S, D, Request, Response] =
    new State(stateName, stateData, timeout, stopReason, replies)

  def withNotification(notifies: Boolean): State[S, D, Request, Response] =
    if (notifies)
      State(stateName, stateData, timeout, stopReason, replies)
    else
      new SilentState(stateName, stateData, timeout, stopReason, replies)
}
