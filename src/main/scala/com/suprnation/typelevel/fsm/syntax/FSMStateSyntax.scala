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

package com.suprnation.typelevel.fsm.syntax

import cats.effect.Async
import cats.implicits._
import cats.{Monad, Monoid}
import com.suprnation.actor.fsm._
import com.suprnation.actor.fsm.State.replies._

import scala.concurrent.duration.FiniteDuration

trait FSMStateSyntax {
  type Timeout[Request] = Option[(FiniteDuration, Request)]

  final implicit class FSMStateSyntaxOps[F[+_]: Monad, S, D, Request, Response: Monoid](
      sF: F[State[S, D, Request, Response]]
  ) {
    self =>

    def using(nextStateData: D): F[State[S, D, Request, Response]] =
      sF.map(s => s.using(nextStateData))

    def forMax(duration: Option[(FiniteDuration, Request)]): F[State[S, D, Request, Response]] =
      sF.map(s => s.forMax(duration))

    def forMax(
        duration: FiniteDuration,
        timeoutRequest: Request
    ): F[State[S, D, Request, Response]] =
      sF.map(s => s.forMax(duration, timeoutRequest))

    def withNotification(notifies: Boolean): F[State[S, D, Request, Response]] =
      sF.map(_.withNotification(notifies))

    def withStopReason(reason: Reason): F[State[S, D, Request, Response]] =
      sF.map(_.withStopReason(reason))

    def replying(
        replyValue: Response,
        replyType: StateReplyType = SendMessage
    ): F[State[S, D, Request, Response]] =
      sF.map(_.replying(replyValue, replyType))

    def returning(replyValue: Response): F[State[S, D, Request, Response]] =
      sF.map(_.returning(replyValue))
  }

  final implicit def when[F[+_]: Async, S, D, Request, Response: Monoid](
      stateName: S,
      stateTimeout: Timeout[Request] = Option.empty[(FiniteDuration, Request)]
  )(
      stateFunction: StateManager[F, S, D, Request, Response] => PartialFunction[
        FSM.Event[D, Request],
        F[State[S, D, Request, Response]]
      ]
  ): FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response]().when(stateName, stateTimeout)(stateFunction)

  final implicit def when[F[+_]: Async, S, D, Request, Response: Monoid](
      stateName: S,
      stateTimeout: FiniteDuration,
      onTimeout: Request
  )(
      stateFunction: StateManager[F, S, D, Request, Response] => PartialFunction[
        FSM.Event[D, Request],
        F[State[S, D, Request, Response]]
      ]
  ): FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response]().when(stateName, Some(stateTimeout -> onTimeout))(
      stateFunction
    )

}
