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

import cats.effect._
import cats.effect.std.{Console, Supervisor}
import cats.syntax.all._
import cats.Monad
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.engine.ActorCell

import scala.annotation.unchecked.uncheckedVariance

/** Message envelopes may implement this trait for better logging, such as logging of message class name of the wrapped message instead of the envelope class name.
  */
trait WrappedMessage {
  def message: Any
}

/** Subscribe to this class to be notified about all [[DeadLetter]] (also the suppressed ones) and [[Dropped]].
  *
  * Not for user extension
  */
trait AllDeadLetters[F[+_]] extends WrappedMessage {
  def message: Any

  def sender: Option[NoSendActorRef[F]]

  def recipient: Receiver[F]
}

/** When a message is sent to an Actor that is terminated before receiving the message, it will be sent as a DeadLetter to the ActorSystem's eventstream.
  */
final case class DeadLetter[F[+_]](
    message: Any,
    sender: Option[NoSendActorRef[F]],
    recipient: Receiver[F]
) extends AllDeadLetters[F] {}

/** Similar to [[DeadLetter]] with the slight twist of NOT being logged by the default dead letters listener. Messages which end up being suppressed dead letters are internal messages for which ending up as dead-letter is both expected and harmless.
  *
  * It is possible to subscribe to suppressed dead letters on the ActorSystem's EventStream explicitly.
  */
@SerialVersionUID(1L)
final case class SuppressedDeadLetter[F[+_]](
    message: DeadLetterSuppression,
    sender: Option[NoSendActorRef[F]],
    recipient: Receiver[F]
) extends AllDeadLetters[F] {}

/** Envelope that is published on the eventStream wrapped in [[DeadLetter]] for every message that is dropped due to overfull queues or routers with no routees.
  *
  * When this message was sent without a sender [[ReplyingActorRef]], `sender` will be `ActorRef.noSender`, i.e. `null`.
  */
final case class Dropped[F[+_]](
    message: Any,
    reason: String,
    sender: Option[NoSendActorRef[F]],
    recipient: Receiver[F]
) extends AllDeadLetters[F]

/** Use with caution: Messages extending this trait will not be logged by the default dead-letters listener. Instead they will be wrapped as [[SuppressedDeadLetter]] and may be subscribed for explicitly.
  */
trait DeadLetterSuppression {}

/** Create an opaque type so that we are able to have another implicit (besides the sender. )
  */
case class Receiver[F[+_]](actorRef: NoSendActorRef[F]) extends AnyVal

object ActorRef {
  type NoSendActorRef[F[+_]] = ReplyingActorRef[F, Nothing, Any]
  type ActorRef[F[+_], -Request] = ReplyingActorRef[F, Request, Any]
}

trait ReplyingActorRef[F[+_], -Request, +Response] {
  implicit def receiver: Receiver[F] = Receiver(this)

  implicit def monadEvidence: Monad[F]

  val path: ActorPath

  /** The path for the parent actor.
    */
  def parent: ActorPath

  def !(message: => Request)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Unit]

  def !*(message: => SystemCommand)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Unit]

  /** Forward a message from an original actor.
    *
    * @param message
    *   the message to forward.
    * @param context
    *   the context
    */
  def forward(message: => Request)(implicit
      context: MinimalActorContext[F, Nothing, Any]
  ): F[Unit] =
    this.!(message)(context.sender)

  /** Forward a message from an original actor.
    *
    * @param message
    *   the message to forward.
    * @param context
    *   the context
    */
  def >>!(message: => Request)(implicit context: MinimalActorContext[F, Nothing, Any]): F[Unit] =
    this.forward(message)(context)

  /** Shuts down the actor with its message queu.
    *
    * @return
    */
  def stop: F[Unit]

  /** Narrow the type of this `ActorRef`, which is always a safe operation.
    */
  def narrowRequest[U <: Request]: ActorRef[F, U]

  /** Unsafe utility method for widening the type accepted by this ActorRef request;
    * provided to avoid having to use `asInstanceOf` on the full reference type,
    * which would unfortunately also work on non-ActorRefs. Use it with caution,it may cause a [[java.lang.ClassCastException]] when you send a message
    * to the widened ReplyingActorRef[F, U, Response].
    */
  def widenRequest[U >: Request @uncheckedVariance]: ActorRef[F, U]

  // Here we could use a Message[_] but not sure.. check the type.
  def ?(fa: => Request)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Response]

  def ?*(fa: => Any)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Any]

  /** Wait for the computation to be processed by the inbound queue and disregard the result.
    *
    * @param fa
    *   the computation to be sent and processed by the queue.
    */
  def ?!(fa: => Request)(implicit
      sender: Option[ActorRef[F, Nothing]] = None
  ): F[Any] = ?*(fa)

  /** Narrow the type of this `ActorRef`, which is always a safe operation.
    */
  def widenResponse[U >: Response]: ReplyingActorRef[F, Request, U]

  /** Unsafe utility method for widening the type accepted by this ActorRef response;
    * provided to avoid having to use `asInstanceOf` on the full reference type,
    * which would unfortunately also work on non-ActorRefs. Use it with caution,it may cause a [[java.lang.ClassCastException]] when you send a message
    * to the widened ReplyingActorRef[F, Request, U].
    */
  def narrowResponse[U <: Response @uncheckedVariance]: ReplyingActorRef[F, Request, U]

}

object InternalActorRef {
  def apply[F[+_]: Async: Temporal: Console, Request, Response](
      supervisor: Supervisor[F],
      systemShutdownSignal: Deferred[F, Unit],
      name: String,
      props: F[ReplyingActor[F, Request, Response]],
      system: ActorSystem[F],
      path: ActorPath,
      parent: Option[NoSendActorRef[F]],
      sendSupervise: Boolean = true
  ): F[InternalActorRef[F, Request, Response]] =
    for {
      cellRef <- Ref.of[F, Option[ActorCell[F, Request, Response]]](None)
      localActorRef = InternalActorRef[F, Request, Response](
        supervisor,
        systemShutdownSignal,
        name,
        props,
        system,
        path,
        cellRef
      )
      cell <- ActorCell[F, Request, Response](
        supervisor,
        systemShutdownSignal,
        localActorRef,
        props,
        system,
        parent
      )
      _ <- localActorRef.actorCellRef.update(_ => Some(cell))
      _ <- cell.init(sendSupervise)
    } yield localActorRef
}

case class InternalActorRef[F[+_]: Async: Temporal: Console, Request, Response](
    supervisor: Supervisor[F],
    systemShutdownSignal: Deferred[F, Unit],
    name: String,
    props: F[ReplyingActor[F, Request, Response]],
    system: ActorSystem[F],
    override val path: ActorPath,
    actorCellRef: Ref[F, Option[ActorCell[F, Request, Response]]]
) extends ReplyingActorRef[F, Request, Response] { self =>

  override implicit def monadEvidence: Monad[F] = implicitly[Monad[F]]

  val start: F[Fiber[F, Throwable, Unit]] = assertCellActiveAndDo(actorCell => actorCell.start)
  val stop: F[Unit] = assertCellActiveAndDo(actorCell => actorCell.stop)

  def ?(fa: => Request)(implicit sender: Option[NoSendActorRef[F]] = None): F[Response] = (this ?* fa).map(_.asInstanceOf[Response])

  def ?*(fa: => Any)(implicit sender: Option[NoSendActorRef[F]] = None): F[Any] = assertCellActiveAndDo[Any](actorCell =>
    for {
      // Here we need to add the logic not to push to the queue anymore if it shutdown...
      deferred <- Deferred[F, Any]
      _ <- actorCell.sendMessage(Envelope(fa, sender, receiver), Option(deferred))
      result <- deferred.get
    } yield result
  )

  /** Send a system message.
    */
  def sendSystemMessage(invocation: SystemMessageEnvelope[F]): F[Unit] =
    assertCellActiveAndDo(actorCell => actorCell.sendSystemMessage(invocation).void)

  def !(fa: => Request)(implicit sender: Option[NoSendActorRef[F]] = None): F[Unit] =
    assertCellActiveAndDo(actorCell => actorCell.sendMessage(Envelope(fa, sender, receiver), None))

  def !*(fa: => SystemCommand)(implicit sender: Option[NoSendActorRef[F]] = None): F[Unit] =
    assertCellActiveAndDo(actorCell =>
      actorCell.sendMessage(Envelope(fa, sender, receiver), None)
    ).void

  def resume(causedByFailure: Option[Throwable]): F[Unit] =
    assertCellActiveAndDo(actorCell => actorCell.resume(causedByFailure))

  def suspend(causedByFailure: Option[Throwable] = Option.empty[Throwable]): F[Unit] =
    assertCellActiveAndDo(actorCell => actorCell.suspend(causedByFailure))

  def assertCellActiveAndDo[A](fn: ActorCell[F, Request, Response] => F[A]): F[A] = for {
    cell <- actorCellRef.get
    result <- cell match {
      case None =>
        Concurrent[F].raiseError(new Error("System not initialised via the actor system.  "))
      case Some(actorCell) => fn(actorCell)
    }
  } yield result

  def restart(cause: Option[Throwable]): F[Unit] =
    assertCellActiveAndDo(actorCell => actorCell.restart(cause))

  override def parent: ActorPath = path.parent

  override def toString: String = s"[System: ${system.name}] [Path: $path] [name: $name]}"

  override def narrowRequest[U <: Request]: ActorRef[F, U] = this

  override def widenRequest[U >: Request]: ActorRef[F, U] =
    this.asInstanceOf[ActorRef[F, U]]

  override def widenResponse[U >: Response]: ReplyingActorRef[F, Request, U] = this

  override def narrowResponse[U <: Response]: ReplyingActorRef[F, Request, U] =
    this.asInstanceOf[ReplyingActorRef[F, Request, U]]

}
