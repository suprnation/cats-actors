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

package com.suprnation.typelevel.fsm.instances

import cats.Parallel
import cats.effect.{Async, Sync, Temporal}
import cats.implicits._
import cats.kernel.Semigroup
import com.suprnation.actor.fsm.FSM.StopEvent
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig, Reason, State}
import com.suprnation.actor.SupervisionStrategy

trait FSMBuilderInstances {
  final implicit def FSMBuilderSemigroupEvidence[F[
      +_
  ]: Parallel: Async: Temporal, S, D, Request, Response]
      : Semigroup[FSMBuilder[F, S, D, Request, Response]] =
    new Semigroup[FSMBuilder[F, S, D, Request, Response]] {

      override def combine(
          x: FSMBuilder[F, S, D, Request, Response],
          y: FSMBuilder[F, S, D, Request, Response]
      ): FSMBuilder[F, S, D, Request, Response] = {
        val terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] = { case msg =>
          x.terminateEvent(msg) >> y.terminateEvent(msg)
        }

        val terminationCallback: Option[Reason => F[Unit]] =
          (x.onTerminationCallback, y.onTerminationCallback) match {
            case (None, None)       => None
            case (l, None)          => l
            case (None, r)          => r
            case (Some(l), Some(r)) => Some((reason: Reason) => l(reason) >> r(reason))
          }

        val onError: Function2[Throwable, Option[Any], F[Unit]] =
          (t: Throwable, message: Option[Any]) => x.onError(t, message) >> y.onError(t, message)

        val supervision: Option[SupervisionStrategy[F]] =
          (x.supervisorStrategy, y.supervisorStrategy) match {
            case (None, None) => None
            case (l, None)    => l
            case (None, r)    => r
            case (
                  Some(l),
                  Some(r)
                ) => // No straightforward way to combine potentially arbitrary supervision strategies
              // We override with the latest
              Some(r)
          }

        FSMBuilder[F, S, D, Request, Response](
          config = FSMConfig[F, S, D, Request, Response](
            x.config.debug || y.config.debug,
            (event: Any, sender, state: State[S, D, Request, Response]) => {
              Async[F].whenA(x.config.debug)(x.config.receive(event, sender, state))
              Async[F].whenA(y.config.debug)(y.config.receive(event, sender, state))
            },
            (oldState: State[S, D, Request, Response], newState: State[S, D, Request, Response]) =>
              Async[F].whenA(x.config.debug)(x.config.transition(oldState, newState)) >>
                Async[F].whenA(y.config.debug)(y.config.transition(oldState, newState))
          ),
          stateFunctions = x.stateFunctions ++ y.stateFunctions,
          stateTimeouts = x.stateTimeouts ++ y.stateTimeouts,
          transitionEvent = x.transitionEvent ++ y.transitionEvent,
          terminateEvent = terminateEvent,
          onTerminationCallback = terminationCallback,
          preStart = x.preStart >> y.preStart,
          postStop = x.postStop >> y.postStop,
          onError = onError,
          supervisorStrategy = supervision
        )
      }

    }

}
