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

package com.suprnation.actor.dungeon

import cats.effect.kernel.Concurrent
import cats.effect.std.Console
import cats.effect.{Async, Deferred, Temporal}
import cats.syntax.all._
import com.suprnation.actor.Actor.Message
import com.suprnation.actor.Exception.Catcher
import com.suprnation.actor.dispatch._
import com.suprnation.actor.dispatch.mailbox.{Mailbox, Mailboxes}
import com.suprnation.actor.dungeon.Dispatch.DispatchContext
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.Error
import com.suprnation.actor.{Envelope, EnvelopeWithDeferred, SystemMessageEnvelope}

import scala.util.control.{NoStackTrace, NonFatal}

object Dispatch {

  def createContext[F[+_]: Async: Temporal: Console](name: String): F[DispatchContext[F]] =
    Dispatch.createMailbox[F](name).map(DispatchContext(_))

  private def createMailbox[F[+_]: Async: Temporal: Console](
      name: String
  ): F[Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]]] =
    Mailboxes.createMailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]](name)

  case class DispatchContext[F[+_]: Async: Temporal: Console](
      var mailbox: Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]]
  ) {

    def swapMailbox(
        _mailbox: Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]]
    ): F[Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]]] =
      Temporal[F]
        .delay {
          val previousMailbox
              : Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]] = mailbox
          mailbox = _mailbox
          previousMailbox
        }

  }
}

trait Dispatch[F[+_]] {
  actorCell: ActorCell[F] =>

  implicit val asyncF: Async[F]
  implicit val concurrentF: Concurrent[F]
  implicit val dispatchContext: DispatchContext[F]
  implicit val consoleF: Console[F]

  /** Initialise the cell, i.e. setup the mailboxes and supervision. The UID must be reasonably different from the previous UID of a possible ctor with the same path.
    */
  def init(sendSupervise: Boolean): F[Unit] =
    for {
      _ <- create(None)
      _ <-
        if (sendSupervise) {
          parent.sendSystemMessage(Envelope.system(SystemMessage.Supervise(self)))
        } else concurrentF.unit
    } yield ()

  override def hasMessages: F[Boolean] = dispatchContext.mailbox.hasMessage

  final def numberOfMessages: F[Int] = dispatchContext.mailbox.numberOfMessages

  final def isTerminated: F[Boolean] = dispatchContext.mailbox.isClosed

  final def suspend(causeByFailure: Option[Throwable]): F[Unit] =
    this
      .sendSystemMessage(Envelope.system(SystemMessage.Suspend(causeByFailure)))
      .recoverWith(handleException)

  final def resume(causedByFailure: Option[Throwable]): F[Unit] =
    this
      .sendSystemMessage(Envelope.system(SystemMessage.Resume(causedByFailure)))
      .recoverWith(handleException)

  final def restart(cause: Option[Throwable]): F[Unit] =
    this
      .sendSystemMessage(Envelope.system(SystemMessage.Recreate(cause)))
      .recoverWith(handleException)

  final def stop: F[Unit] =
    this.sendSystemMessage(Envelope.system(SystemMessage.Terminate())).recoverWith(handleException)

  override def sendSystemMessage(invocation: SystemMessageEnvelope[F]): F[Unit] =
    // Here we need to see if the system is terminated... if it is we need to send all messages to the deadletter.
    dispatchContext.mailbox.systemEnqueue(invocation).recoverWith(handleException)

  def ?[A](fa: Message): F[A] =
    // TODO: this should also be under the recoverWith - check what we can do.
    for {
      deferred <- Deferred[F, A]
      _ <- sendMessage(
        Envelope[F, Message](fa, self),
        Option(deferred).asInstanceOf[Option[Deferred[F, Any]]]
      )
      result <- deferred.get
    } yield result

  override def sendMessage(msg: Envelope[F, Message], deferred: Option[Deferred[F, Any]]): F[Unit] =
    // Here we need to see if the system is terminated... if it is we send all messages to the dead letter
    dispatchContext.mailbox
      .enqueue(EnvelopeWithDeferred(msg, deferred))
      .recoverWith(handleException)

  private def handleException: Catcher[F, Unit] = { case NonFatal(e) =>
    for {
      a <- actor
      message = e match {
        case n: NoStackTrace => "swallowing exception during message send: " + n.getMessage
        case _ => "swallowing exception during message send" // stack trace includes message
      }
      _ <- publish(Error(e, a.self.path.toString, _, message))
    } yield ()

  }

}
