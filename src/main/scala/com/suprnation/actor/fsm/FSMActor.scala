package com.suprnation.actor.fsm

import cats.Parallel
import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.{ActorLogging, ReplyingActor}
import FSM.{Event, StopEvent}
import State.StateTimeoutWithSender
import com.suprnation.typelevel.actors.syntax.ActorRefSyntaxOps
import com.suprnation.typelevel.fsm.syntax._

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration
import cats.Applicative

final class FSMActorState[F[+_], S, D, Request, Response](
    val currentStateRef: Ref[F, State[S, D, Request, Response]],
    val nextStateRef: Ref[F, Option[State[S, D, Request, Response]]],
    val generationRef: Ref[F, Int],
    val timeoutFiberRef: Ref[F, Option[Fiber[F, Throwable, Unit]]]
)

object FSMActorState {

  def initialize[F[+_]: Sync, S, D, Request, Response](
      stateName: S,
      stateData: D,
      timeout: Option[(FiniteDuration, Request)] = None
  ): F[FSMActorState[F, S, D, Request, Response]] =
    for {
      currentStateRef <- Ref.of[F, State[S, D, Request, Response]](
        State(stateName, stateData, timeout)
      )
      nextStateRef <- Ref.of[F, Option[State[S, D, Request, Response]]](None)
      generationRef <- Ref.of[F, Int](0)
      timeoutFiberRef <- Ref.of[F, Option[Fiber[F, Throwable, Unit]]](None)
    } yield new FSMActorState(currentStateRef, nextStateRef, generationRef, timeoutFiberRef)
}

object FSMActor {

  type Constructor[F[+_], S, D, Request, Response] =
    Function2[FSMBuilder[F, S, D, Request, Response], FSMActorState[F, S, D, Request, Response], F[
      FSMActor[F, S, D, Request, Response]
    ]]

  def defaultConstructor[F[+_]: Async: Parallel: Temporal, S, D, Request, Response]
      : Constructor[F, S, D, Request, Response] = (builder, state) =>
    Async[F].pure(new FSMActor(builder, state))

}

/**  Actor capturing the necessary logic for an FSM as constructed within an FSMBuilder.
  *
  *  Extending this actor allows us to implement custom logic and state (e.g. a custom preStart).
  *
  *  @note CAUTION: When overriding actor methods, ensure you call the suprclass methods in FSMActor.
  *  Otherwise the FSM functionality will fail and the actor will be in an inconsistent state.
  */
class FSMActor[F[+_]: Async: Parallel: Temporal, S, D, Request, Response](
    builder: FSMBuilder[F, S, D, Request, Response],
    state: FSMActorState[F, S, D, Request, Response]
) extends Actor[F, Any]
    with ActorLogging[F, Any] {

  type StateFunction[R] =
    PartialFunction[(FSM.Event[D, R], StateManager[F, S, D, Request, Response]), F[
      State[S, D, Request, Response]
    ]]

  // State manager will allow us to move from state to state.
  val stateManager: StateManager[F, S, D, Request, Response] =
    new StateManager[F, S, D, Request, Response] {
      override def goto(nextStateName: S): F[State[S, D, Request, Response]] =
        state.currentStateRef.get.map(currentState => State(nextStateName, currentState.stateData))

      override def stay(): F[State[S, D, Request, Response]] =
        (state.currentStateRef.get >>= (currentState => goto(currentState.stateName)))
          .withNotification(false)

      override def stop(reason: Reason): F[State[S, D, Request, Response]] =
        state.currentStateRef.get >>= (currentState => stop(reason, currentState.stateData))

      override def stop(reason: Reason, stateData: D): F[State[S, D, Request, Response]] =
        stay().using(stateData).withStopReason(reason)

      override def forMax(
          finiteDuration: Option[(FiniteDuration, Request)]
      ): F[State[S, D, Request, Response]] =
        stay().map(_.forMax(finiteDuration))

      override def stayAndReply(replyValue: Response): F[State[S, D, Request, Response]] =
        stay().map(_.replying(replyValue))
    }

  private def cancelTimeout: F[Unit] =
    state.timeoutFiberRef.get.flatMap(_.fold(Sync[F].unit)(_.cancel))

  private def handleTransition(prev: S, next: S): F[Unit] =
    builder.transitionEvent.collect {
      case te if te.isDefinedAt((prev, next)) => te((prev, next))
    }.sequence_

  private val handleEvent: StateFunction[Any] = { case (Event(value, _), _) =>
    for {
      currentState <- state.currentStateRef.get
      _ <- log(
        s"[Warning] unhandled [Event $value] [State: ${currentState.stateName}] [Data: ${currentState.stateData}]"
      )
    } yield currentState
  }

  override def preStart: F[Unit] = state.currentStateRef.get >>= makeTransition

  override def receive: ReplyingReceive[F, Any, Any] = {
    case TimeoutMarker(gen, sender, msg) =>
      state.generationRef.get
        .map(_ == gen)
        .ifM(
          processMsg(StateTimeoutWithSender(sender, msg), "state timeout"),
          Sync[F].unit
        )

    case value =>
      cancelTimeout >> state.generationRef.update(_ + 1) >> processMsg(value, sender)
  }

  private def processMsg(value: Any, source: AnyRef): F[Any] =
    state.currentStateRef.get >>= (currentState =>
      (if (builder.config.debug) builder.config.receive(value, sender, currentState)
       else Sync[F].unit)
        >> processEvent(Event(value, currentState.stateData), source)
    )

  private def processEvent(event: FSM.Event[D, Any], @unused source: AnyRef): F[Any] =
    for {
      currentState <- state.currentStateRef.get
      stateFunc = builder.stateFunctions(currentState.stateName)
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
            stateFunc
              .lift((updatedEvent, stateManager))
              .getOrElse(
                handleEvent((updatedEvent.asInstanceOf[FSM.Event[D, Any]], stateManager))
              )
        case _ =>
          stateFunc
            .lift((event.asInstanceOf[Event[D, Request]], stateManager))
            .getOrElse(handleEvent((event, stateManager)))
      }

      _ <- applyState(state)
    } yield ()

  private def applyState(nextState: State[S, D, Request, Response]): F[Unit] =
    nextState.stopReason match {
      case None =>
        makeTransition(nextState)

      case Some(_) =>
        val sendReplies: F[Unit] =
          nextState.replies.reverse.traverse_(r => sender.get.widenRequest[Response] ! r)
        sendReplies >> terminate(nextState) >> context.stop(self)
    }

  def makeTransition(nextState: State[S, D, Request, Response]): F[Unit] = {
    def scheduleTimeout(timeoutData: (FiniteDuration, Request)): F[Unit] =
      cancelTimeout >>
        (for {
          generation <- state.generationRef.get
          currentSender = sender
          cancelFn <- context.system.scheduler.scheduleOnce_(timeoutData._1)(
            self !* TimeoutMarker[F, Request](generation, currentSender, timeoutData._2)
          )
          _ <- state.timeoutFiberRef.set(Some(cancelFn))
        } yield ())

    def processStateTransition(currentState: State[S, D, Request, Response]): F[Unit] =
      (if (currentState.stateName != nextState.stateName || nextState.notifies) {
         state.nextStateRef.set(Some(nextState)) >>
           handleTransition(currentState.stateName, nextState.stateName) >>
           state.nextStateRef.set(None)
       } else Sync[F].unit) >>
        (if (builder.config.debug) builder.config.transition(currentState, nextState)
         else Sync[F].unit) >>
        state.currentStateRef.set(nextState) >>
        (nextState.timeout match {
          case Some((d: FiniteDuration, msg)) if d.length >= 0 =>
            scheduleTimeout(d -> msg)
          case _ =>
            builder.stateTimeouts(nextState.stateName).fold(Sync[F].unit)(scheduleTimeout)
        })

    builder.stateFunctions.get(nextState.stateName) match {
      case None =>
        val errorMessage = s"Next state ${nextState.stateName} does not exist"
        stateManager.stay().withStopReason(Failure(errorMessage)) >>= terminate

      case Some(_) =>
        // If the sender is not not specified (e.g. if you are running directly from an application) we will ignore it to be safe.
        nextState.replies.reverse.traverse_(r =>
          sender.fold(Sync[F].unit)(sender =>
            {
              // The sender knew that we could send him the Response so this is totally safe.
              sender.widenRequest[Response] ! r
            }.void
          )
        ) >>
          state.currentStateRef.get.flatMap(processStateTransition)
    }

  }

  protected def onTerminated(reason: Reason): F[Unit] =
    builder.onTerminationCallback.fold(Sync[F].unit)(x => x(reason))

  override def postStop: F[Unit] =
    (stateManager.stay().withStopReason(Shutdown) >>= terminate) >> super.postStop

  private def terminate(nextState: State[S, D, Request, Response]): F[Unit] =
    for {
      currentState <- state.currentStateRef.get
      _ <- currentState.stopReason.fold {
        nextState.stopReason.fold(Sync[F].unit) { reason =>
          for {
            _ <- cancelTimeout
            _ <- onTerminated(reason)
            _ <- state.currentStateRef.set(nextState)
            stopEvent = StopEvent(reason, currentState.stateName, currentState.stateData)
            _ <- builder.terminateEvent.lift(stopEvent).getOrElse(Sync[F].unit)
          } yield ()
        }
      }(_ => Sync[F].unit)
    } yield ()
}
