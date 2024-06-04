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
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.fsm.FSM.{Event, StopEvent}
import com.suprnation.actor.fsm.State.{StateTimeout, StateTimeoutWithSender}
import com.suprnation.actor.{Actor, ActorLogging, ActorRef}
import com.suprnation.typelevel.actors.syntax.ActorRefSyntaxOps
import com.suprnation.typelevel.fsm.syntax._

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

object FSM {

  type StateFunction[F[+_], S, D] = PartialFunction[(Any, StateManager[F, S, D]), F[State[S, D]]]

  def apply[F[+_]: Parallel: Async: Temporal, S, D]: FSMBuilder[F, S, D] = new FSMBuilder[F, S, D]()

  /** All messages sent to the FSM will be wrapped inside an `Event`, which allows pattern matching to extract both state and data.
    */
  final case class Event[D](event: Any, stateData: D)

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

  def apply[F[+_]: Parallel: Async: Temporal, S, D](): FSMBuilder[F, S, D] =
    new FSMBuilder[F, S, D](
      FSMConfig(
        receive = (_: Any, _: Option[ActorRef[F]], _: State[S, D]) => Sync[F].unit,
        transition = (_: State[S, D], _: State[S, D]) => Sync[F].unit
      )
    )

}

object FSMConfig {

  def noDebug[F[+_], S, D]: FSMConfig[F, S, D] = FSMConfig(
    debug = false,
    (e: Any, sender: Option[ActorRef[F]], s: State[S, D]) =>
      throw new Error("Not used just for compilation"),
    (oS: State[S, D], nS: State[S, D]) => throw new Error("Not used just for compilation")
  )

  def withConsoleInformation[F[+_]: Console, S, D]: FSMConfig[F, S, D] = FSMConfig(
    debug = true,
    receive = (event: Any, sender: Option[ActorRef[F]], currentState: State[S, D]) =>
      Console[F].println(
        s"[FSM] Received [Event: $event] [Sender: $sender] [CurrentState: ${currentState.stateName}] [CurrentData: ${currentState.stateData}]"
      ),
    transition = (oldState: State[S, D], newState: State[S, D]) =>
      Console[F].println(
        s"[FSM] Transition ([CurrentState: ${oldState.stateName}] -> [CurrentData: ${oldState.stateData}]) ~> ([NextState: ${newState.stateName}] -> [NextData: ${newState.stateData}])"
      )
  )

}

case class FSMConfig[F[+_], S, D](
    debug: Boolean = false,
    receive: (Any, Option[ActorRef[F]], State[S, D]) => F[Unit],
    transition: (State[S, D], State[S, D]) => F[Unit]
)

// In this version we will separate the description from the execution.
case class FSMBuilder[F[+_]: Parallel: Async: Temporal, S, D](
    config: FSMConfig[F, S, D] = FSMConfig.noDebug[F, S, D],
    stateFunctions: Map[S, PartialFunction[(FSM.Event[D], StateManager[F, S, D]), F[State[S, D]]]] =
      Map.empty[S, PartialFunction[(FSM.Event[D], StateManager[F, S, D]), F[State[S, D]]]],
    stateTimeouts: Map[S, Option[FiniteDuration]] = Map.empty[S, Option[FiniteDuration]],
    transitionEvent: List[PartialFunction[(S, S), F[Unit]]] = Nil,
    terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] = FSM.NullFunction,
    onTerminationCallback: Option[Reason => F[Unit]] = Option.empty[Reason => F[Unit]]
) { builderSelf =>

  type Event = FSM.Event[D]
  type StateFunction = PartialFunction[(Event, StateManager[F, S, D]), F[State[S, D]]]
  type Timeout = Option[FiniteDuration]
  type TransitionHandler = PartialFunction[(S, S), F[Unit]]

  def when(stateName: S, stateTimeout: FiniteDuration)(
      stateFunction: StateFunction
  ): FSMBuilder[F, S, D] =
    register(stateName, stateFunction, Some(stateTimeout))

  private def register(name: S, function: StateFunction, timeout: Timeout): FSMBuilder[F, S, D] =
    if (stateFunctions contains name) {
      FSMBuilder[F, S, D](
        config,
        stateFunctions + (name -> stateFunctions(name).orElse(function)),
        stateTimeouts + (name -> timeout.orElse(stateTimeouts(name))),
        transitionEvent,
        terminateEvent,
        onTerminationCallback
      )
    } else {
      FSMBuilder[F, S, D](
        config,
        stateFunctions + (name -> function),
        stateTimeouts + (name -> timeout),
        transitionEvent,
        terminateEvent,
        onTerminationCallback
      )
    }

  def when(stateName: S, stateTimeout: Timeout = Option.empty[FiniteDuration])(
      stateFunction: StateFunction
  ): FSMBuilder[F, S, D] =
    register(stateName, stateFunction, stateTimeout)

  final def onTransition(transitionHandler: TransitionHandler): FSMBuilder[F, S, D] =
    FSMBuilder[F, S, D](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent ++ List(transitionHandler),
      terminateEvent,
      onTerminationCallback
    )

  def withConfig(config: FSMConfig[F, S, D]): FSMBuilder[F, S, D] =
    FSMBuilder[F, S, D](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent,
      terminateEvent,
      onTerminationCallback
    )

  def onTermination(onTerminated: Reason => F[Unit]): FSMBuilder[F, S, D] =
    FSMBuilder[F, S, D](
      config,
      stateFunctions,
      stateTimeouts,
      transitionEvent,
      terminateEvent,
      onTerminationCallback = Option(onTerminated)
    )

  final def startWith(stateName: S, stateData: D, timeout: Timeout = None): PreCompiledFSM = {
    new PreCompiledFSM { preCompiledSelf =>
      override def initialize: F[Actor[F] with ActorLogging[F]] = for {
        currentStateRef <- Ref.of[F, State[S, D]](State(stateName, stateData, timeout))
        nextStateRef <- Ref.of[F, Option[State[S, D]]](None)
        generationRef <- Ref.of[F, Int](0)
        timeoutFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
      } yield {
        new Actor[F] with ActorLogging[F] {
          // State manager will allow us to move from state to state.
          val stateManager: StateManager[F, S, D] = new StateManager[F, S, D] {
            override def goto(nextStateName: S): F[State[S, D]] =
              currentStateRef.get.map(currentState => State(nextStateName, currentState.stateData))

            override def stay(): F[State[S, D]] =
              (currentStateRef.get >>= (currentState => goto(currentState.stateName)))
                .withNotification(false)

            override def stop(reason: Reason): F[State[S, D]] =
              currentStateRef.get >>= (currentState => stop(Normal, currentState.stateData))

            override def stop(reason: Reason, stateData: D): F[State[S, D]] =
              stay().using(stateData).withStopReason(reason)

            override def forMax(finiteDuration: Option[FiniteDuration]): F[State[S, D]] =
              stay().map(_.forMax(finiteDuration))

            override def stayAndReply(replyValue: Any): F[State[S, D]] =
              stay().map(_.replying(replyValue))
          }

          private def cancelTimeout: F[Unit] =
            timeoutFiberRef.get.flatMap(_.fold(Sync[F].unit)(_.cancel))

          private def handleTransition(prev: S, next: S): F[Unit] =
            transitionEvent.collect {
              case te if te.isDefinedAt((prev, next)) => te((prev, next))
            }.sequence_

          private val handleEvent: StateFunction = { case (Event(value, _), _) =>
            for {
              currentState <- currentStateRef.get
              _ <- log(
                s"[Warning] unhandled [Event $value] [State: ${currentState.stateName}] [Data: ${currentState.stateData}]"
              )
            } yield currentState
          }

          override def preStart: F[Unit] = currentStateRef.get >>= makeTransition

          override def receive: Receive[F] = {
            case TimeoutMarker(gen, sender) =>
              generationRef.get
                .map(_ == gen)
                .ifM(
                  processMsg(StateTimeoutWithSender(sender), "state timeout"),
                  Sync[F].unit
                )

            case value =>
              cancelTimeout >> generationRef.update(_ + 1) >> processMsg(value, sender)
          }

          private def processMsg(value: Any, source: AnyRef): F[Any] =
            currentStateRef.get >>= (currentState =>
              (if (config.debug) config.receive(value, sender, currentState) else Sync[F].unit)
                >> processEvent(Event(value, currentState.stateData), source)
            )

          private def processEvent(event: Event, @unused source: AnyRef): F[Any] =
            for {
              currentState <- currentStateRef.get
              stateFunc = stateFunctions(currentState.stateName)
              // If this is a Timeout event we will set the original sender on the cell so that the user can reply to the original sender.
              state <- event.event match {
                case StateTimeoutWithSender(sender) =>
                  /* The StateTimeoutWithSender was only used to propagate the sender information, the actual user does not need to understand these
                   * semantics. */
                  // We will simplify the message to the SenderTimeout, the sender will be implicit on the cell.
                  val updatedEvent: FSM.Event[D] = event.copy(event = StateTimeout)
                  /* Override the sender to be the same sender as the original state (we might want to notify him that something happened in the
                   * timeout) */
                  (self.cell >>= (c =>
                    Async[F]
                      .delay(c.creationContext.senderOp = sender.asInstanceOf[Option[ActorRef[F]]])
                  )) >>
                    stateFunc
                      .lift((updatedEvent, stateManager))
                      .getOrElse(handleEvent((updatedEvent, stateManager)))
                case _ =>
                  stateFunc
                    .lift((event, stateManager))
                    .getOrElse(handleEvent((event, stateManager)))
              }

              _ <- applyState(state)
            } yield ()

          private def applyState(nextState: State[S, D]): F[Unit] =
            nextState.stopReason match {
              case None =>
                makeTransition(nextState)

              case Some(_) =>
                val sendReplies: F[Unit] = nextState.replies.reverse.traverse_(r => sender.get ! r)
                sendReplies >> terminate(nextState) >> context.stop(self)
            }

          def makeTransition(nextState: State[S, D]): F[Unit] = {
            def scheduleTimeout(d: FiniteDuration): F[Unit] =
              cancelTimeout >>
                (for {
                  generation <- generationRef.get
                  currentSender = sender
                  cancelFn <- context.system.scheduler.scheduleOnce_(d)(
                    self ! TimeoutMarker[F](generation, currentSender)
                  )
                  _ <- timeoutFiberRef.set(Some(cancelFn))
                } yield ())

            def processStateTransition(currentState: State[S, D]): F[Unit] =
              (if (currentState.stateName != nextState.stateName || nextState.notifies) {
                 nextStateRef.set(Some(nextState)) >>
                   handleTransition(currentState.stateName, nextState.stateName) >>
                   nextStateRef.set(None)
               } else Sync[F].unit) >>
                (if (config.debug) config.transition(currentState, nextState) else Sync[F].unit) >>
                currentStateRef.set(nextState) >>
                (currentState.timeout match {
                  case Some(d: FiniteDuration) if d.length >= 0 => scheduleTimeout(d)
                  case _ =>
                    stateTimeouts(currentState.stateName).fold(Sync[F].unit)(scheduleTimeout)
                })

            stateFunctions.get(nextState.stateName) match {
              case None =>
                val errorMessage = s"Next state ${nextState.stateName} does not exist"
                stateManager.stay().withStopReason(Failure(errorMessage)) >>= terminate

              case Some(_) =>
                // If the sender is not not specified (e.g. if you are running directly from an application) we will ignore it to be safe.
                nextState.replies.reverse.traverse_(r => sender.fold(Sync[F].unit)(_ ! r)) >>
                  currentStateRef.get.flatMap(processStateTransition)
            }

          }

          protected def onTerminated(reason: Reason): F[Unit] =
            onTerminationCallback.fold(Sync[F].unit)(x => x(reason))

          override def postStop: F[Unit] =
            (stateManager.stay().withStopReason(Shutdown) >>= terminate) >> super.postStop

          private def terminate(nextState: State[S, D]): F[Unit] =
            for {
              currentState <- currentStateRef.get
              _ <- currentState.stopReason.fold {
                nextState.stopReason.fold(Sync[F].unit) { reason =>
                  for {
                    _ <- cancelTimeout
                    _ <- onTerminated(reason)
                    _ <- currentStateRef.set(nextState)
                    stopEvent = StopEvent(reason, currentState.stateName, currentState.stateData)
                    _ <- terminateEvent.lift(stopEvent).getOrElse(Sync[F].unit)
                  } yield ()
                }
              }(_ => Sync[F].unit)
            } yield ()
        }
      }
    }
  }

  trait PreCompiledFSM {

    def initialize: F[Actor[F]]
  }

}
