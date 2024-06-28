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

package com.suprnation.actor

import cats.effect.std.Console
import cats.effect.{Concurrent, Temporal}
import cats.syntax.all._
import cats.{Monad, MonadThrow, Parallel}
import com.suprnation.actor.Actor.{Actor, Receive, ReplyingReceive}
import com.suprnation.actor.ActorRef.NoSendActorRef

import java.util.UUID

object Behaviour {
  def emptyBehavior[F[+_]: MonadThrow, Request, Response]: PartialFunction[Request, F[Response]] =
    new ReplyingReceive[F, Request, Response] {
      def isDefinedAt(x: Request): Boolean = false

      def apply(x: Request): F[Response] = MonadThrow[F].raiseError(
        new UnsupportedOperationException(
          s"[Message: $x] is not supported.  Please update the apply() method in the Receive partial function to be able to handle this.  "
        )
      )
    }

  def ignoringBehaviour[F[+_]: Monad: Console, Request](
      prefix: String
  ): Receive[F, Request] = new Receive[F, Request] {
    def isDefinedAt(x: Request): Boolean = true

    def apply(msg: Request): F[Request] =
      Console[F]
        .println(
          s"[Prefix: $prefix] Received [Message: $msg] and ignoring.  If this is not your intention please consider updating the Receive partial function or update the aroundReceive method.   "
        )
        .as(msg)
  }
}

object ReplyingActor {
  val ACTOR_NOT_INITIALIZED = new Error(
    "Actor not set.  Possibly actor has been stopped or not initialised.  "
  )

  def empty[F[+_]: Parallel: Concurrent: Temporal, Request, Response]
      : ReplyingActor[F, Request, Response] =
    withReceive(
      Behaviour.emptyBehavior
    )

  def ignoring[F[+_]: Parallel: Concurrent: Temporal: Console, Request](
      name: String
  ): Actor[F, Request] =
    withReceive(Behaviour.ignoringBehaviour(name))

  def withReceive[F[+_]: Parallel: Concurrent: Temporal, Request, Response](
      _receive: PartialFunction[Request, F[Response]]
  ): ReplyingActor[F, Request, Response] = new ReplyingActor[F, Request, Response] {
    override def receive: ReplyingReceive[F, Request, Response] = _receive
  }
}

object Actor {
  type Receive[F[+_], -Request] = PartialFunction[Request, F[Any]]
  type ReplyingReceive[F[+_], -Request, +Response] = PartialFunction[Request, F[Response]]
  type Actor[F[+_], Request] = ReplyingActor[F, Request, Any]

  def empty[F[+_]: Parallel: Concurrent: Temporal, Request]: Actor[F, Request] =
    withReceive(
      Behaviour.emptyBehavior
    )

  def ignoring[F[+_]: Parallel: Concurrent: Temporal: Console, Request]
      : ReplyingActor[F, Request, Any] =
    withReceive(
      Behaviour.ignoringBehaviour(UUID.randomUUID().toString)
    )

  def withReceive[F[+_]: Parallel: Concurrent: Temporal, Request](
      _receive: PartialFunction[Request, F[Any]]
  ): Actor[F, Request] = new Actor[F, Request] {
    override def receive: Receive[F, Request] = _receive
  }

}

/** Actor base class that should be extended by or mixed to create an Actor with semantics of the 'Actor Model' <a href="https://en.wikipedia.org/wiki/Actor_model">https://en.wikipedia.org/wiki/Actor_model</a>
  *
  * An actor has a well-defined (non-cyclic) lifecycle
  *   - ''RUNNING'' (created and started actor) - can receive messages
  *   - ''SHUTDOWN'' (when 'stop' is invoked) - can't do anything
  *
  * The Actor's own [[ReplyingActorRef]] is available as `self`, the current message's sender as `sender()` and the [[ActorContext]] as `context`. The only abstract method is `receive` which shall return the initial behaviour of the actor as a partial function (behaviour can be changed using `context.become` and `context.unbecome`).
  */
abstract class ReplyingActor[F[+_]: Concurrent: Parallel: Temporal, Request, Response] {

  // These two properties we have to set them manually
  // We do this because we do not want a Ref for the context to make the DX easier.
  // We also do not want a Ref on self to make the DX easier.
  implicit val context: ActorContext[F, Request, Response] = null
  final val self: ReplyingActorRef[F, Request, Response] = null
  implicit def implicitSelf: Option[ReplyingActorRef[F, Request, Response]] = Option(self)

  def init: F[Unit] = Concurrent[F].unit

  def sender: Option[NoSendActorRef[F]] = context.sender

  def receive: ReplyingReceive[F, Request, Response] = Behaviour.emptyBehavior[F, Request, Response]

  /** Internal API
    *
    * Can be override to intercept calls to the actor errors when receiving messages.
    */
  def onError(reason: Throwable, message: Option[Any]): F[Unit] = Monad[F].unit

  /** User overridable definition the strategy to use for supervising child actors.
    */
  def supervisorStrategy: SupervisionStrategy[F] = SupervisorStrategy.defaultStrategy[F]

  /** Internal API
    *
    * Can be override to intercept calls to the actor's current behaviour.
    *
    * @param receive
    *   current behaviour
    * @param msg
    *   current message
    */
  @inline private[actor] def aroundReceive(
      receive: Receive[F, Request],
      msg: Any
  ): F[Any] =
    receive.applyOrElse(msg.asInstanceOf[Request], unhandled)

  /** User overridable callback. <p/> Is called when a message isn't handled by the current behaviour of the actor by default it fails with either [[com.suprnation.actor.DeathPactException]] (in case of an unhandled [[com.suprnation.actor.Terminated]] message) or publishes an [[com.suprnation.actor.UnhandledMessage]] to the actor system's event stream.
    */
  def unhandled(message: Any): F[Any] = message match {
    case Terminated(dead, _) => MonadThrow[F].raiseError(DeathPactException(dead))
    case _ =>
      context.system.eventStream
        .offer(UnhandledMessage(message, context.sender, self).toString)
  }

  /** Internal API
    *
    * Can be override to intercept calls to `preStart`. Calls `preStart` by default
    *
    * @return
    */
  private[actor] def aroundPreStart(): F[Unit] = preStart

  /** Internal API
    *
    * Can be override to intercept calls to `postStop`. Calls `postStop` by default.
    *
    * @return
    */
  private[actor] def aroundPostStop(): F[Unit] = postStop

  /** User overridable callback. <p/> Is called asynchronously after `actor.stop` is invoked.
    *
    * Empty default implementation.
    */
  def postStop: F[Unit] = Monad[F].unit

  /** Internal API.
    *
    * Can be overridden to intercept calls to `preRestart`. Call `preRestart` by default.
    */
  private[actor] def aroundPreRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    preRestart(reason, message)

  /** User overridable callback '''By default it disposes of all children and then calls `postStop()`'''
    *
    * @param reason
    *   the Throwable that caused the restart to happen
    * @param message
    *   optionally the current message the actor processed when failing, if applicable.
    *
    * <p/>
    *
    * Is called on a crashed Actor right BEFORE it is restarted to allow cleanup of resource before the Actor is terminated.
    */
  def preRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    for {
      children <- context.children
      _ <- children.toList
        .parTraverse_ { (child: NoSendActorRef[F]) =>
          context.unwatch(child) >> context.stop(child)
        }
      _ <- postStop
    } yield ()

  /** Internal API
    *
    * Can be overriden to intercept calls to `postRestart`. Calls `postRestart` by default.
    */
  private[actor] def aroundPostRestart(reason: Option[Throwable]): F[Unit] = postRestart(reason)

  /** User overrideable callback: By default it calls `preStart()`.
    *
    * @param reason
    *   the Throwable that cause the restart to happen
    *
    * <p/> Is called right AFTER restart on the newly created Actor to allow re-initialisation after an Actor crash.
    * @return
    */
  def postRestart(reason: Option[Throwable]): F[Unit] = preStart

  /** User overridable callback. <p/> Is called when an Actor is started. Actors are automatically started asynchronously when created. Empty default implementation.
    */
  def preStart: F[Unit] = Monad[F].unit

  /** Internal API
    *
    * Can be overriden to intercept calls to the preSuspend. Calls `preSuspend` by default.
    */
  private[actor] def aroundPreSuspend(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    preSuspend(reason, message)

  /** User overridble callback. <p/> Is called when an Actor is suspended.
    */
  def preSuspend(reason: Option[Throwable], message: Option[Any]): F[Unit] = Monad[F].unit

  /** Internal API
    *
    * Can be overriden to intercept calls to `preResume`. Calls `postResume` by default.
    */
  private[actor] def aroundPreResume(): F[Unit] = preResume

  /** User overridble callback. <p/> Is called when an Actor is resumed.
    */
  def preResume: F[Unit] = Monad[F].unit

  /** Widen this actor to allow a more general set of messages to be sent.
    * @tparam U a superclass of Request
    * @return the widened actor
    */
  def widen[U >: Request]: ReplyingActor[F, U, Response] =
    this.asInstanceOf[ReplyingActor[F, U, Response]]

}
