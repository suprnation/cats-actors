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

package com.suprnation.actor.fsm.test

import cats.{Functor, Monoid}
import cats.implicits._
import com.suprnation.actor.fsm.{FSM, State}

case class FSMTestKit[F[+_]: Functor, S, D, Request, Response : Monoid](
    private val fsm: FSM[F, S, D, Request, Response]
) {

  def currentState: F[(S, D)] =
    fsm.currentStateRef.get.map(s => (s.stateName, s.stateData))

  def setState(stateName: S, stateData: D): F[Unit] =
    fsm.currentStateRef.set(State(stateName, stateData))

}
