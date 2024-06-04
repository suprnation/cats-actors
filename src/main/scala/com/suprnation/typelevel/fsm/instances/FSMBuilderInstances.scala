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
import cats.kernel.Semigroup
import cats.syntax.all._
import com.suprnation.actor.fsm.FSM.StopEvent
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig, State}

trait FSMBuilderInstances {
  final implicit def FSMBuilderSemigroupEvidence[F[+_]: Parallel: Async: Temporal, S, D]
      : Semigroup[FSMBuilder[F, S, D]] = new Semigroup[FSMBuilder[F, S, D]] {
    def optionallyExecute(condition: Boolean, effect: F[Unit]): F[Unit] =
      if (condition) effect else Sync[F].unit

    override def combine(x: FSMBuilder[F, S, D], y: FSMBuilder[F, S, D]): FSMBuilder[F, S, D] = {
      val terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] = { case msg =>
        x.terminateEvent(msg) >> y.terminateEvent(msg)
      }

      FSMBuilder[F, S, D](
        FSMConfig[F, S, D](
          x.config.debug || y.config.debug,
          (event: Any, sender, state: State[S, D]) => {
            optionallyExecute(x.config.debug, x.config.receive(event, sender, state))
            optionallyExecute(y.config.debug, y.config.receive(event, sender, state))
          },
          (oldState: State[S, D], newState: State[S, D]) =>
            optionallyExecute(x.config.debug, x.config.transition(oldState, newState)) >>
              optionallyExecute(y.config.debug, y.config.transition(oldState, newState))
        ),
        x.stateFunctions ++ y.stateFunctions,
        x.stateTimeouts ++ y.stateTimeouts,
        x.transitionEvent ++ y.transitionEvent,
        terminateEvent
      )
    }

  }

}
