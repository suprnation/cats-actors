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

import cats.Monoid
import cats.implicits._
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.fsm.State.SilentState
import com.suprnation.typelevel.fsm.syntax.Timeout

import scala.concurrent.duration.FiniteDuration

object State {
  def apply[S, D, Request, Response: Monoid](
      stateName: S,
      stateData: D,
      timeout: Timeout[Request] = None,
      stopReason: Option[Reason] = None
  ): State[S, D, Request, Response] =
    new State(
      stateName,
      stateData,
      timeout,
      stopReason,
      Monoid[Response].empty,
      Monoid[Response].empty
    )

  def unapply[S, D, Request, Response](
      state: State[S, D, Request, Response]
  ): Option[(S, D, Timeout[Request], Option[Reason], Response, Response)] =
    Some(
      (
        state.stateName,
        state.stateData,
        state.timeout,
        state.stopReason,
        state.replies,
        state.returns
      )
    )

  case class StateTimeoutWithSender[F[+_], Request](
      original: Option[NoSendActorRef[F]],
      msg: Request
  )

  class SilentState[S, D, Request, Response: Monoid](
      stateName: S,
      stateData: D,
      timeout: Timeout[Request],
      stopReason: Option[Reason],
      replies: Response, // These will be sent as messages
      returns: Response // These will be returned and form replies if this state was triggered by an ask
  ) extends State[S, D, Request, Response](
        stateName,
        stateData,
        timeout,
        stopReason,
        replies,
        returns
      ) {

    override def notifies: Boolean = false

    override def copy(
        stateName: S = stateName,
        stateData: D = stateData,
        timeout: Timeout[Request] = timeout,
        stopReason: Option[Reason] = stopReason,
        replies: Response = replies,
        returns: Response = returns
    ): State[S, D, Request, Response] =
      new SilentState(stateName, stateData, timeout, stopReason, replies, returns)

  }

  case object StateTimeout

  object replies {
    sealed trait StateReplyType
    case object SendMessage extends StateReplyType
    case object ReturnResponse extends StateReplyType
    case object BothMessageAndResponse extends StateReplyType
  }
}

class State[S, D, Request, Response: Monoid](
    val stateName: S,
    val stateData: D,
    val timeout: Timeout[Request],
    val stopReason: Option[Reason],
    val replies: Response,
    val returns: Response
) {

  import State.replies._

  def notifies: Boolean = true

  /** Send a reply to the sender of the current message, if available.
    *
    * The reply can either be sent as a message or returned as a response via the receiver return type.
    *
    * @param replyValue
    * @param replyType
    * @return
    */
  def replying(
      replyValue: Response,
      replyType: StateReplyType = SendMessage
  ): State[S, D, Request, Response] = replyType match {
    case SendMessage    => copy(replies = replies |+| replyValue)
    case ReturnResponse => copy(returns = returns |+| replyValue)
    case BothMessageAndResponse =>
      copy(replies = replies |+| replyValue, returns = returns |+| replyValue)
  }

  def returning(replyValue: Response): State[S, D, Request, Response] =
    replying(replyValue, ReturnResponse)

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
      replies: Response = replies,
      returns: Response = returns
  ): State[S, D, Request, Response] =
    new State(stateName, stateData, timeout, stopReason, replies, returns)

  def withNotification(notifies: Boolean): State[S, D, Request, Response] =
    if (notifies)
      new State(stateName, stateData, timeout, stopReason, replies, returns)
    else
      new SilentState(stateName, stateData, timeout, stopReason, replies, returns)
}
