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

import cats.Monoid
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.Console
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.dungeon.TimerSchedulerImpl.StoredTimer
import com.suprnation.actor.fsm.FSM.{Event, StopEvent}
import com.suprnation.actor.fsm.State.StateTimeoutWithSender
import com.suprnation.actor.fsm.State.replies._
import com.suprnation.actor.{
  ActorLogging,
  MinimalActorContext,
  ReplyingActor,
  SupervisionStrategy,
  Timers
}
import com.suprnation.typelevel.actors.syntax.ActorRefSyntaxOps
import com.suprnation.typelevel.fsm.syntax._

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration

object FSM {

  type StateFunction[F[+_], S, D, Request, Response] =
    StateManager[F, S, D, Request, Response] => PartialFunction[Any, F[
      State[S, D, Request, Response]
    ]]

  type TransitionHandler[F[+_], S, D, Request, Response] =
    PartialFunction[(S, S), TransitionContext[F, S, D, Request, Response] => F[Unit]]

  def apply[F[+_]: Async, S, D, Request, Response: Monoid]: FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response]()

  /** All messages sent to the FSM will be wrapped inside an `Event`, which allows pattern matching to extract both state and data.
    */
  final case class Event[D, Request](event: Request, stateData: D)

  /** Case class representing the state of the FSM within the `onTermination` block
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

private sealed abstract class FSM[F[+_]: Async, S, D, Request, Response: Monoid]
    extends Actor[F, Any]
    with ActorLogging[F, Any]
    with Timers[F, Any, Any, String] {

  type StateFunction[R] =
    StateManager[F, S, D, Request, Response] => PartialFunction[FSM.Event[D, R], F[
      State[S, D, Request, Response]
    ]]
  protected type Timeout = Option[(FiniteDuration, Request)]
  protected type TransitionHandler = FSM.TransitionHandler[F, S, D, Request, Response]

  protected val config: FSMConfig[F, S, D, Request, Response]
  protected val stateFunctions: Map[S, StateManager[F, S, D, Request, Response] => PartialFunction[
    FSM.Event[D, Request],
    F[State[S, D, Request, Response]]
  ]]
  protected val stateTimeouts: Map[S, Option[(FiniteDuration, Request)]]
  protected val transitionEvent: List[TransitionHandler]
  protected val terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]]
  protected val onTerminationCallback: Option[(Reason, D) => F[Unit]]

  protected val customPreStart: PreStartContext[F, S, D, Request, Response] => F[State[S, D, Request, Response]]
  protected val customPostStop: StateContext[F, S, D, Request, Response] => F[Unit]
  protected val customOnError: Function2[Throwable, Option[Any], F[Unit]]
  protected val customSupervisorStrategy: Option[SupervisionStrategy[F]]

  val currentStateRef: Ref[F, State[S, D, Request, Response]]
  val nextStateRef: Ref[F, Option[State[S, D, Request, Response]]]
  protected val generationRef: Ref[F, Int]
  protected val timeoutFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]]

  // State manager will allow us to move from state to state.
  private val stateManager: StateManager[F, S, D, Request, Response] =
    new StateManager[F, S, D, Request, Response] {

      override def actorContext: MinimalActorContext[F, Request, Response] =
        context.asInstanceOf[MinimalActorContext[F, Request, Response]]

      override def stateName: F[S] = currentStateRef.get.map(_.stateName)

      override def stateData: F[D] = currentStateRef.get.map(_.stateData)

      override def goto(nextStateName: S): F[State[S, D, Request, Response]] =
        currentStateRef.get.map(currentState => State(nextStateName, currentState.stateData))

      override def stay(): F[State[S, D, Request, Response]] =
        (currentStateRef.get >>= (currentState => goto(currentState.stateName)))
          .withNotification(false)

      override def stop(reason: Reason): F[State[S, D, Request, Response]] =
        currentStateRef.get >>= (currentState => stop(reason, currentState.stateData))

      override def stop(reason: Reason, stateData: D): F[State[S, D, Request, Response]] =
        stay().using(stateData).withStopReason(reason)

      override def forMax(
          finiteDuration: Option[(FiniteDuration, Request)]
      ): F[State[S, D, Request, Response]] =
        stay().map(_.forMax(finiteDuration))

      override def stayAndReply(
          replyValue: Response,
          replyType: StateReplyType = SendMessage
      ): F[State[S, D, Request, Response]] =
        stay().map(_.replying(replyValue))

      override def stayAndReturn(replyValue: Response): F[State[S, D, Request, Response]] =
        stay().map(_.returning(replyValue))

      override def startSingleTimer(
          name: String,
          msg: Request,
          delay: FiniteDuration
      ): F[Unit] =
        (
          if (config.debug) config.startTimer(name, msg, delay, false)
          else Async[F].unit
        ) >> timers.startSingleTimer(name, msg, delay)

      override def startTimerWithFixedDelay(
          name: String,
          msg: Request,
          delay: FiniteDuration
      ): F[Unit] =
        (
          if (config.debug) config.startTimer(name, msg, delay, true)
          else Async[F].unit
        ) >> timers.startTimerWithFixedDelay(name, msg, delay)

      override def cancelTimer(name: String): F[Unit] =
        (if (config.debug) config.cancelTimer(name) else Async[F].unit) >>
          timers.cancel(name)

      override def isTimerActive(name: String): F[Boolean] =
        timers.isTimerActive(name)
    }

  private def cancelTimeout: F[Unit] =
    timeoutFiberRef.get.flatMap(_.fold(Sync[F].unit)(_.cancel))

  private def cancelTimers: F[Unit] = timers.cancelAll

  private def handleTransition(prev: S, next: S, nextStateData: D): F[Unit] = {
    lazy val context: TransitionContext[F, S, D, Request, Response] =
      TransitionContext.fromManager(stateManager, nextStateData)
    transitionEvent.collect {
      case te if te.isDefinedAt((prev, next)) => te((prev, next))(context)
    }.sequence_
  }

  private val handleEvent: StateFunction[Any] = _ => { case Event(value, _) =>
    for {
      currentState <- currentStateRef.get
      _ <- log(
        s"[Warning] unhandled [Event $value] [State: ${currentState.stateName}] [Data: ${currentState.stateData}]"
      )
    } yield currentState
  }

  override def preStart: F[Unit] =
    (customPreStart(stateManager) >>= makeTransition).void

  override def receive: ReplyingReceive[F, Any, Response] = {
    case TimeoutMarker(gen, sender, msg) =>
      generationRef.get
        .map(_ == gen)
        .ifM(
          processMsg(StateTimeoutWithSender(sender, msg), "state timeout"),
          Monoid[Response].empty.pure[F]
        )

    case value =>
      cancelTimeout >> generationRef.update(_ + 1) >> processMsg(value, sender)
  }

  private def processMsg(value: Any, source: AnyRef): F[Response] =
    currentStateRef.get >>= (currentState =>
      (if (config.debug) config.receive(value, sender, currentState) else Sync[F].unit)
        >> processEvent(Event(value, currentState.stateData), source)
    )

  private def processEvent(
      event: FSM.Event[D, Any],
      @unused source: AnyRef
  ): F[Response] =
    for {
      currentState <- currentStateRef.get
      stateFunc = stateFunctions(currentState.stateName)
      // If this is a Timeout event we will set the original sender on the cell so that the user can reply to the original sender.
      state <- event.event match {
        case StateTimeoutWithSender(sender, msg) =>
          /* The StateTimeoutWithSender was only used to propagate the sender information, the actual user does not need to understand these
           * semantics. */
          // We will simplify the message to the SenderTimeout, the sender will be implicit on the cell.
          val updatedEvent: FSM.Event[D, Request] =
            event.copy(event = msg.asInstanceOf[Request])
          /* Override the sender to be the same sender as the original state (we might want to notify him that something happened in the
           * timeout) */
          (self.cell >>= (c =>
            Async[F]
              .delay(c.creationContext.senderOp = sender.asInstanceOf[Option[ActorRef[F, Nothing]]])
          )) >>
            stateFunc(stateManager)
              .lift(updatedEvent)
              .getOrElse(
                handleEvent(stateManager)(updatedEvent.asInstanceOf[FSM.Event[D, Any]])
              )

        case _ =>
          stateFunc(stateManager)
            .lift(event.asInstanceOf[Event[D, Request]])
            .getOrElse(handleEvent(stateManager)(event))
      }

      replies <- applyState(state)
    } yield replies

  private def applyState(nextState: State[S, D, Request, Response]): F[Response] =
    nextState.stopReason match {
      case None =>
        makeTransition(nextState)

      case Some(_) =>
        val sendReplies: F[Unit] =
          sender.fold(Sync[F].unit)(sender => sender.widenRequest[Response] ! nextState.replies)
        sendReplies >> terminate(nextState) >> context.stop(self) >> nextState.returns
          .pure[F]
    }

  def makeTransition(nextState: State[S, D, Request, Response]): F[Response] = {
    def scheduleTimeout(timeoutData: (FiniteDuration, Request)): F[Unit] =
      cancelTimeout >>
        (for {
          generation <- generationRef.get
          currentSender = sender
          cancelFn <- context.system.scheduler.scheduleOnce_(timeoutData._1)(
            self !* TimeoutMarker[F, Request](generation, currentSender, timeoutData._2)
          )
          _ <- timeoutFiberRef.set(Some(cancelFn))
        } yield ())

    def processStateTransition(currentState: State[S, D, Request, Response]): F[Unit] =
      (if (currentState.stateName != nextState.stateName || nextState.notifies) {
         nextStateRef.set(Some(nextState)) >>
           handleTransition(currentState.stateName, nextState.stateName, nextState.stateData) >>
           nextStateRef.set(None)
       } else Sync[F].unit) >>
        (if (config.debug) config.transition(currentState, nextState) else Sync[F].unit) >>
        currentStateRef.set(nextState) >>
        (nextState.timeout match {
          case Some((d: FiniteDuration, msg)) if d.length >= 0 =>
            scheduleTimeout(d -> msg)
          case _ =>
            stateTimeouts(nextState.stateName).fold(Sync[F].unit)(scheduleTimeout)
        })

    stateFunctions.get(nextState.stateName) match {
      case None =>
        val errorMessage = s"Next state ${nextState.stateName} does not exist"
        (stateManager.stay().withStopReason(Failure(errorMessage)) >>= terminate) >>
          Monoid[Response].empty.pure[F]

      case Some(_) =>
        // If the sender is not not specified (e.g. if you are running directly from an application) we will ignore it to be safe.
        sender.fold(Sync[F].unit)(sender =>
          // The sender knew that we could send him the Response so this is totally safe.
          sender.widenRequest[Response] ! nextState.replies
        ) >>
          currentStateRef.get.flatMap(processStateTransition) >>
          nextState.returns.pure[F]
    }

  }

  protected def onTerminated(reason: Reason, stateData: D): F[Unit] =
    onTerminationCallback.fold(Sync[F].unit)(x => x(reason, stateData))

  override def postStop: F[Unit] =
    (stateManager
      .stay()
      .withStopReason(Shutdown) >>= terminate) >> customPostStop(stateManager) >> super.postStop

  private def terminate(nextState: State[S, D, Request, Response]): F[Unit] =
    for {
      currentState <- currentStateRef.get
      _ <- currentState.stopReason.fold {
        nextState.stopReason.fold(Sync[F].unit) { reason =>
          for {
            _ <- cancelTimers
            _ <- cancelTimeout
            _ <- onTerminated(reason, nextState.stateData)
            _ <- currentStateRef.set(nextState)
            stopEvent = StopEvent(reason, nextState.stateName, nextState.stateData)
            _ <- terminateEvent.lift(stopEvent).getOrElse(Sync[F].unit)
          } yield ()
        }
      }(_ => Sync[F].unit)
    } yield ()

  override def supervisorStrategy: SupervisionStrategy[F] =
    customSupervisorStrategy.getOrElse(super.supervisorStrategy)

  override def onError(reason: Throwable, message: Option[Any]): F[Unit] =
    customOnError(reason, message)
}

object FSMBuilder {

  def apply[F[+_]: Async, S, D, Request, Response: Monoid]()
      : FSMBuilder[F, S, D, Request, Response] =
    FSMBuilder[F, S, D, Request, Response](
      config = FSMConfig(
        receive =
          (_: Any, _: Option[NoSendActorRef[F]], _: State[S, D, Request, Response]) => Sync[F].unit,
        transition =
          (_: State[S, D, Request, Response], _: State[S, D, Request, Response]) => Sync[F].unit,
        startTimer = (_: String, _: Request, _: FiniteDuration, _: Boolean) => Sync[F].unit,
        cancelTimer = (_: String) => Sync[F].unit
      ),
      stateFunctions = Map.empty[S, StateManager[F, S, D, Request, Response] => PartialFunction[
        FSM.Event[D, Request],
        F[State[S, D, Request, Response]]
      ]],
      stateTimeouts = Map.empty[S, Option[(FiniteDuration, Request)]],
      transitionEvent = Nil,
      terminateEvent = FSM.NullFunction,
      onTerminationCallback = Option.empty[(Reason, D) => F[Unit]],
      preStart = (c: PreStartContext[F, S, D, Request, Response]) => c.stay(),
      postStop = (_: StateContext[F, S, D, Request, Response]) => Async[F].unit,
      onError = (_: Throwable, _: Option[Any]) => Async[F].unit,
      supervisorStrategy = None
    )
}

// In this version we will separate the description from the execution.
case class FSMBuilder[F[+_]: Async, S, D, Request, Response: Monoid](
    config: FSMConfig[F, S, D, Request, Response],
    stateFunctions: Map[S, StateManager[F, S, D, Request, Response] => PartialFunction[
      FSM.Event[D, Request],
      F[State[S, D, Request, Response]]
    ]],
    stateTimeouts: Map[S, Option[(FiniteDuration, Request)]],
    transitionEvent: List[FSM.TransitionHandler[F, S, D, Request, Response]],
    terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]],
    onTerminationCallback: Option[(Reason, D) => F[Unit]],
    preStart: PreStartContext[F, S, D, Request, Response] => F[State[S, D, Request, Response]],
    postStop: StateContext[F, S, D, Request, Response] => F[Unit],
    onError: Function2[Throwable, Option[Any], F[Unit]],
    supervisorStrategy: Option[SupervisionStrategy[F]]
) { builderSelf =>

  type StateFunction[R] =
    StateManager[F, S, D, Request, Response] => PartialFunction[FSM.Event[D, R], F[
      State[S, D, Request, Response]
    ]]
  type Timeout = Option[(FiniteDuration, Request)]
  type TransitionHandler = FSM.TransitionHandler[F, S, D, Request, Response]

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
      preStart: PreStartContext[F, S, D, Request, Response] => F[State[S, D, Request, Response]]
  ): FSMBuilder[F, S, D, Request, Response] =
    copy(
      preStart = preStart
    )

  def withPostStop(
      postStop: StateContext[F, S, D, Request, Response] => F[Unit]
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
        timerRefInit <- Ref.of[F, Map[String, StoredTimer[F]]](Map())
        timerGenInit <- Ref.of[F, Int](0)
      } yield new FSM[F, S, D, Request, Response] {
        override val config: FSMConfig[F, S, D, Request, Response] = builderSelf.config
        override val stateFunctions
            : Map[S, StateManager[F, S, D, Request, Response] => PartialFunction[
              Event[D, Request],
              F[State[S, D, Request, Response]]
            ]] = builderSelf.stateFunctions
        override protected val stateTimeouts: Map[S, Option[(FiniteDuration, Request)]] =
          builderSelf.stateTimeouts
        override protected val transitionEvent
            : List[FSM.TransitionHandler[F, S, D, Request, Response]] =
          builderSelf.transitionEvent
        override protected val terminateEvent: PartialFunction[StopEvent[S, D], F[Unit]] =
          builderSelf.terminateEvent
        override protected val onTerminationCallback: Option[(Reason, D) => F[Unit]] =
          builderSelf.onTerminationCallback
        override protected val customPreStart: PreStartContext[F, S, D, Request, Response] => F[State[S, D, Request, Response]] =
          builderSelf.preStart
        override protected val customPostStop: StateContext[F, S, D, Request, Response] => F[Unit] =
          builderSelf.postStop
        override protected val customSupervisorStrategy: Option[SupervisionStrategy[F]] =
          builderSelf.supervisorStrategy
        override protected val customOnError: (Throwable, Option[Any]) => F[Unit] =
          builderSelf.onError
        override val currentStateRef: Ref[F, State[S, D, Request, Response]] = currentState
        override val nextStateRef: Ref[F, Option[State[S, D, Request, Response]]] = nextState
        override protected val generationRef: Ref[F, Int] = generation
        override protected val timeoutFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]] =
          timeoutFiber
        override protected val timersRef: Ref[F, Map[String, StoredTimer[F]]] = timerRefInit
        override protected val timerGenRef: Ref[F, Int] = timerGenInit
        override val asyncEvidence: Async[F] = implicitly[Async[F]]
      }.asInstanceOf[ReplyingActor[F, Request, Response]]
    }

  trait PreCompiledFSM {
    def initialize: F[ReplyingActor[F, Request, Response]]
  }

}

object FSMConfig {

  def noDebug[F[+_], S, D, Request, Response]: FSMConfig[F, S, D, Request, Response] = FSMConfig(
    debug = false,
    (e: Any, sender: Option[NoSendActorRef[F]], s: State[S, D, Request, Response]) =>
      throw new Error("Not used just for compilation"),
    (oS: State[S, D, Request, Response], nS: State[S, D, Request, Response]) =>
      throw new Error("Not used just for compilation"),
    (n: String, m: Request, t: FiniteDuration, r: Boolean) =>
      throw new Error("Not used just for compilation"),
    (cT: String) => throw new Error("Not used just for compilation")
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
      ),
    startTimer = (
        name: String,
        message: Request,
        timeout: FiniteDuration,
        repeat: Boolean
    ) =>
      Console[F].println(s"""[FSM] Starting ${if (repeat) "repeating "
        else ""}timer [Name: ${name}] [Duration: ${timeout}] [Message: ${message}]"""),
    cancelTimer = (timer: String) => Console[F].println(s"[FSM] Cancelling timer [$timer]")
  )

}

case class FSMConfig[F[+_], S, D, Request, Response](
    debug: Boolean = false,
    receive: (Any, Option[NoSendActorRef[F]], State[S, D, Request, Response]) => F[Unit],
    transition: (State[S, D, Request, Response], State[S, D, Request, Response]) => F[Unit],
    startTimer: (String, Request, FiniteDuration, Boolean) => F[Unit],
    cancelTimer: String => F[Unit]
)
