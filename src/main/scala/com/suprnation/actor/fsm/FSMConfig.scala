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

import cats.effect.std.Console
import com.suprnation.actor.ActorRef.NoSendActorRef

object FSMConfig {

  def noDebug[F[+_], S, D, Request, Response]: FSMConfig[F, S, D, Request, Response] = FSMConfig(
    debug = false,
    (e: Any, sender: Option[NoSendActorRef[F]], s: State[S, D, Request, Response]) =>
      throw new Error("Not used just for compilation"),
    (oS: State[S, D, Request, Response], nS: State[S, D, Request, Response]) =>
      throw new Error("Not used just for compilation")
  )

  def withConsoleInformation[F[+_]: Console, S, D, Request, Response]
      : FSMConfig[F, S, D, Request, Response] = FSMConfig(
    debug = true,
    receive = (
        event: Any,
        sender: Option[NoSendActorRef[F]],
        currentState: State[S, D, Request, Response]
    ) =>
      Console[F].println(
        s"[FSM] Received [Event: $event] [Sender: $sender] [CurrentState: ${currentState.stateName}] [CurrentData: ${currentState.stateData}]"
      ),
    transition = (
        oldState: State[S, D, Request, Response],
        newState: State[S, D, Request, Response]
    ) =>
      Console[F].println(
        s"[FSM] Transition ([CurrentState: ${oldState.stateName}] -> [CurrentData: ${oldState.stateData}]) ~> ([NextState: ${newState.stateName}] -> [NextData: ${newState.stateData}])"
      )
  )

}

case class FSMConfig[F[+_], S, D, Request, Response](
    debug: Boolean = false,
    receive: (Any, Option[NoSendActorRef[F]], State[S, D, Request, Response]) => F[Unit],
    transition: (State[S, D, Request, Response], State[S, D, Request, Response]) => F[Unit]
)
