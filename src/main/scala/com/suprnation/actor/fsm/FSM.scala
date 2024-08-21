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

import cats.Parallel
import cats.effect._
import cats.effect.std.Console
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.fsm.FSM.{Event, StopEvent}
import com.suprnation.actor.fsm.State.StateTimeoutWithSender
import com.suprnation.actor.{ActorLogging, ReplyingActor}
import com.suprnation.typelevel.actors.syntax.ActorRefSyntaxOps
import com.suprnation.typelevel.fsm.syntax._

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

object FSM {

  type StateFunction[F[+_], S, D, Request, Response] =
    PartialFunction[(Any, StateManager[F, S, D, Request, Response]), F[
      State[S, D, Request, Response]
    ]]

  def apply[F[+_]: Parallel: Async: Temporal, S, D, Request, Response]
      : FSMBuilder[F, S, D, Request, Response] =
    new FSMBuilder[F, S, D, Request, Response]()

  /** All messages sent to the FSM will be wrapped inside an `Event`, which allows pattern matching to extract both state and data.
    */
  final case class Event[D, Request](event: Request, stateData: D)

  /** Case class representing the state of the FMS within the `onTermination` block
    */
  final case class StopEvent[S, D](reason: Reason, currentState: S, stateData: D)

  object NullFunction extends PartialFunction[Any, Nothing] {
    def isDefinedAt(o: Any) = false
    def apply(o: Any) = sys.error("undefined")
  }

  /** This extractor is just convenience for matching a (S, S) pair, including a reminder what the new state is.
    */
  object `->` {
    def unapply[S](in: (S, S)): Option[(S, S)] = Some(in)
  }

}

object FSMBuilder {

  def apply[F[+_]: Parallel: Async: Temporal, S, D, Request, Response]()
      : FSMBuilder[F, S, D, Request, Response] =
    new FSMBuilder[F, S, D, Request, Response](
      FSMConfig(
        receive =
          (_: Any, _: Option[NoSendActorRef[F]], _: State[S, D, Request, Response]) => Sync[F].unit,
        transition =
          (_: State[S, D, Request, Response], _: State[S, D, Request, Response]) => Sync[F].unit
      )
    )

}

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

// In this version we will separate the description from the execution.
case class FSMBuilder[F[+_]: Parallel: Async: Temporal, S, D, Request, Response](
    config: FSMConfig[F, S, D, Request, Response] = FSMConfig.noDebug[F, S, D, Request, Response],
    stateFunctions: Map[S, PartialFunction[
      (FSM.Event[D, Request], StateManager[F, S, D, Request, Response]),
      F[State[S, D, Request, Response]]
    ]] = Map.empty[S, PartialFunction[
      (FSM.Event[D, Request], StateManager[F, S, D, Request, Response]),
      F[State[S, D, Request, Response]]
    ]],
    stateTimeouts: Map[S, Option[(FiniteDuration, Request)]] =
      Map.empty[S, Option[(FiniteDuration, Request)]],
    transitionEvent: List[PartialFunction[(S, S), F[Unit]]] = Nil,
    terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] = FSM.NullFunction,
    onTerminationCallback: Option[Reason => F[Unit]] = Option.empty[Reason => F[Unit]]
) { builderSelf =>

  type StateFunction[R] =
    PartialFunction[(FSM.Event[D, R], StateManager[F, S, D, Request, Response]), F[
      State[S, D, Request, Response]
    ]]
  type Timeout = Option[(FiniteDuration, Request)]
  type TransitionHandler = PartialFunction[(S, S), F[Unit]]

  def when(stateName: S, stateTimeout: FiniteDuration, onTimeout: Request)(
      stateFunction: StateFunction[Request]
  ): FSMBuilder[F, S, D, Request, Response] =
    register(stateName, stateFunction, Some((stateTimeout, onTimeout)))

  private def register(
      name: S,
      function: StateFunction[Request],
      timeout: Timeout
  ): FSMBuilder[F, S, D, Request, Response] =
    if (stateFunctions contains name) {
      FSMBuilder[F, S, D, Request, Response](
        config,
        stateFunctions + (name -> stateFunctions(name).orElse(function)),
        stateTimeouts + (name -> timeout.orElse(stateTimeouts(name))),
        transitionEvent,
        terminateEvent,
        onTerminationCallback
      )
    } else {
      FSMBuilder[F, S, D, Request, Response](
        config,
        stateFunctions + (name -> function),
        stateTimeouts + (name -> timeout),
        transitionEvent,
        terminateEvent,
        onTerminationCallback
      )
    }

  def when(stateName: S, stateTimeout: Timeout = Option.empty[(FiniteDuration, Request)])(
      stateFunction: StateFunction[Request]
  ): FSMBuilder[F, S, D, Request, Response] =
    register(stateName, stateFunction, stateTimeout)

  final def onTransition(
      transitionHandler: TransitionHandler
  ): FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent ++ List(transitionHandler),
      terminateEvent,
      onTerminationCallback
    )

  def withConfig(
      config: FSMConfig[F, S, D, Request, Response]
  ): FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent,
      terminateEvent,
      onTerminationCallback
    )

  def onTermination(onTerminated: Reason => F[Unit]): FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent,
      terminateEvent,
      onTerminationCallback = Option(onTerminated)
    )

  final def startWith(
      stateName: S,
      stateData: D,
      timeout: Timeout = None,
      actorBuilder: FSMActor.Constructor[F, S, D, Request, Response] =
        FSMActor.defaultConstructor[F, S, D, Request, Response]
  ): PreCompiledFSM =
    new PreCompiledFSM { preCompiledSelf =>
      override def initialize: F[ReplyingActor[F, Request, Response]] =
        (FSMActorState.initialize(stateName, stateData, timeout) >>= {
          state: FSMActorState[F, S, D, Request, Response] => actorBuilder(builderSelf, state)
        }).map(_.asInstanceOf[ReplyingActor[F, Request, Response]])
    }

  trait PreCompiledFSM {

    def initialize: F[ReplyingActor[F, Request, Response]]
  }
}
