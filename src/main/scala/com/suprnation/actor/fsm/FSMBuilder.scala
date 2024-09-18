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
import cats.implicits._
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.fsm.FSM.{Event, StopEvent}
import com.suprnation.actor.{ReplyingActor, SupervisionStrategy}

import scala.concurrent.duration.FiniteDuration

object FSMBuilder {

  def apply[F[+_]: Parallel: Async: Temporal, S, D, Request, Response]()
      : FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response](
      config = FSMConfig(
        receive =
          (_: Any, _: Option[NoSendActorRef[F]], _: State[S, D, Request, Response]) => Sync[F].unit,
        transition =
          (_: State[S, D, Request, Response], _: State[S, D, Request, Response]) => Sync[F].unit
      ),
      stateFunctions = Map.empty[S, StateManager[F, S, D, Request, Response] => PartialFunction[
        FSM.Event[D, Request],
        F[State[S, D, Request, Response]]
      ]],
      stateTimeouts = Map.empty[S, Option[(FiniteDuration, Request)]],
      transitionEvent = Nil,
      terminateEvent = FSM.NullFunction,
      onTerminationCallback = Option.empty[(Reason, D) => F[Unit]],
      preStart = Async[F].unit,
      postStop = Async[F].unit,
      onError = (_: Throwable, _: Option[Any]) => Async[F].unit,
      supervisorStrategy = None
    )

}

// In this version we will separate the description from the execution.
case class FSMBuilder[F[+_]: Parallel: Async, S, D, Request, Response](
    config: FSMConfig[F, S, D, Request, Response],
    stateFunctions: Map[S, StateManager[F, S, D, Request, Response] => PartialFunction[
      FSM.Event[D, Request],
      F[State[S, D, Request, Response]]
    ]],
    stateTimeouts: Map[S, Option[(FiniteDuration, Request)]],
    transitionEvent: List[PartialFunction[(S, S), F[Unit]]],
    terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]],
    onTerminationCallback: Option[(Reason, D) => F[Unit]],
    preStart: F[Unit],
    postStop: F[Unit],
    onError: Function2[Throwable, Option[Any], F[Unit]],
    supervisorStrategy: Option[SupervisionStrategy[F]]
) { builderSelf =>

  type StateFunction[R] =
    StateManager[F, S, D, Request, Response] => PartialFunction[FSM.Event[D, R], F[
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
      copy(
        stateFunctions = this.stateFunctions + (name -> (stateManager =>
          stateFunctions(name)(stateManager).orElse(function(stateManager))
        )),
        stateTimeouts = this.stateTimeouts + (name -> timeout.orElse(stateTimeouts(name)))
      )
    } else {
      copy(
        stateFunctions = this.stateFunctions + (name -> function),
        stateTimeouts = this.stateTimeouts + (name -> timeout)
      )
    }

  def when(stateName: S, stateTimeout: Timeout = Option.empty[(FiniteDuration, Request)])(
      stateFunction: StateFunction[Request]
  ): FSMBuilder[F, S, D, Request, Response] =
    register(stateName, stateFunction, stateTimeout)

  final def onTransition(
      transitionHandler: TransitionHandler
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      transitionEvent = this.transitionEvent ++ List(transitionHandler)
    )

  def withConfig(
      config: FSMConfig[F, S, D, Request, Response]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      config = config
    )

  def onTermination(onTerminated: (Reason, D) => F[Unit]): FSMBuilder[F, S, D, Request, Response] =
    copy(
      onTerminationCallback = Option(onTerminated)
    )

  def withPreStart(
      preStart: F[Unit]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      preStart = preStart
    )

  def withPostStop(
      postStop: F[Unit]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      postStop = postStop
    )

  def onActorError(
      onError: Function2[Throwable, Option[Any], F[Unit]]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      onError = onError
    )

  def withSupervisorStrategy(
      supervisorStrategy: SupervisionStrategy[F]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      supervisorStrategy = supervisorStrategy.some
    )

  final def startWith(stateName: S, stateData: D, timeout: Timeout = None): PreCompiledFSM =
    new PreCompiledFSM { preCompiledSelf =>
      override def initialize: F[ReplyingActor[F, Request, Response]] = for {
        currentState <- Ref.of[F, State[S, D, Request, Response]](
          State(stateName, stateData, timeout)
        )
        nextState <- Ref.of[F, Option[State[S, D, Request, Response]]](None)
        generation <- Ref.of[F, Int](0)
        timeoutFiber <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
      } yield new FSM[F, S, D, Request, Response] {
        override val config: FSMConfig[F, S, D, Request, Response] = builderSelf.config
        override val stateFunctions
            : Map[S, StateManager[F, S, D, Request, Response] => PartialFunction[
              Event[D, Request],
              F[State[S, D, Request, Response]]
            ]] = builderSelf.stateFunctions
        override protected val stateTimeouts: Map[S, Option[(FiniteDuration, Request)]] =
          builderSelf.stateTimeouts
        override protected val transitionEvent: List[PartialFunction[(S, S), F[Unit]]] =
          builderSelf.transitionEvent
        override protected val terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] =
          builderSelf.terminateEvent
        override protected val onTerminationCallback: Option[(Reason, D) => F[Unit]] =
          builderSelf.onTerminationCallback
        override protected val customPreStart: F[Unit] = builderSelf.preStart
        override protected val customPostStop: F[Unit] = builderSelf.postStop
        override protected val customSupervisorStrategy: Option[SupervisionStrategy[F]] =
          builderSelf.supervisorStrategy
        override protected val customOnError: (Throwable, Option[Any]) => F[Unit] =
          builderSelf.onError
        override val currentStateRef: Ref[F, State[S, D, Request, Response]] = currentState
        override val nextStateRef: Ref[F, Option[State[S, D, Request, Response]]] = nextState
        override protected val generationRef: Ref[F, Int] = generation
        override protected val timeoutFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]] =
          timeoutFiber
      }.asInstanceOf[ReplyingActor[F, Request, Response]]
    }

  trait PreCompiledFSM {
    def initialize: F[ReplyingActor[F, Request, Response]]
  }

}
