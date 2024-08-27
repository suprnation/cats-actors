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

import com.suprnation.actor.MinimalActorContext
import com.suprnation.actor.fsm.State.replies._

import scala.concurrent.duration.FiniteDuration

trait StateManager[F[+_], S, D, Request, Response] {

  def minimalContext: MinimalActorContext[F, Request, Response]

  def stateName: F[S]

  def stateData: F[D]

  def forMax(timeoutData: Option[(FiniteDuration, Request)]): F[State[S, D, Request, Response]]

  def goto(nextStateName: S): F[State[S, D, Request, Response]]

  def stay(): F[State[S, D, Request, Response]]

  def stop(): F[State[S, D, Request, Response]] = stop(Normal)

  def stop(reason: Reason): F[State[S, D, Request, Response]]

  def stop(reason: Reason, stateData: D): F[State[S, D, Request, Response]]

  def stayAndReply(
      replyValue: Response,
      replyType: StateReplyType = SendMessage
  ): F[State[S, D, Request, Response]]

  def stayAndReturn(replyValue: Response): F[State[S, D, Request, Response]]

  def startSingleTimer(name: String, msg: Request, delay: FiniteDuration): F[Unit]

  def startTimerWithFixedDelay(name: String, msg: Request, delay: FiniteDuration): F[Unit]

  def cancelTimer(name: String): F[Unit]

  def isTimerActive(name: String): F[Boolean]
}
