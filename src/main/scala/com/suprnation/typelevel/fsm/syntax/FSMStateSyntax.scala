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

import cats.effect.{Async, Temporal}
import cats.implicits._
import cats.{Monad, Parallel}
import com.suprnation.actor.fsm._

import scala.concurrent.duration.FiniteDuration

trait FSMStateSyntax {
  type Timeout = Option[FiniteDuration]

  final implicit class FSMStateSyntaxOps[F[+_]: Parallel: Monad, S, D](sF: F[State[S, D]]) {
    self =>

    def using(nextStateData: D): F[State[S, D]] =
      sF.map(s => s.using(nextStateData))

    def withNotification(notifies: Boolean): F[State[S, D]] =
      sF.map(_.withNotification(notifies))

    def withStopReason(reason: Reason): F[State[S, D]] =
      sF.map(_.withStopReason(reason))

    def replying(replyValue: Any): F[State[S, D]] =
      sF.map(_.replying(replyValue))
  }

  final implicit def when[F[+_]: Parallel: Async: Temporal, S, D](
      stateName: S,
      stateTimeout: Timeout = Option.empty[FiniteDuration]
  )(
      stateFunction: PartialFunction[(FSM.Event[D], StateManager[F, S, D]), F[State[S, D]]]
  ): FSMBuilder[F, S, D] = FSMBuilder[F, S, D]().when(stateName, stateTimeout)(stateFunction)

  final implicit def when[F[+_]: Parallel: Async: Temporal, S, D](
      stateName: S,
      stateTimeout: FiniteDuration
  )(
      stateFunction: PartialFunction[(FSM.Event[D], StateManager[F, S, D]), F[State[S, D]]]
  ): FSMBuilder[F, S, D] = FSMBuilder[F, S, D]().when(stateName, Some(stateTimeout))(stateFunction)

}
