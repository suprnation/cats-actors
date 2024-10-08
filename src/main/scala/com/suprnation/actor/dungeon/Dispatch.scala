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

import cats.effect.std.Console
import cats.effect.{Async, Deferred}
import cats.syntax.all._
import com.suprnation.actor.Exception.Catcher
import com.suprnation.actor.dispatch._
import com.suprnation.actor.dispatch.mailbox.{Mailbox, Mailboxes}
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.Error
import com.suprnation.actor.{Envelope, EnvelopeWithDeferred, SystemMessageEnvelope}
import com.suprnation.typelevel.actors.implicits._

import scala.util.control.{NoStackTrace, NonFatal}

object Dispatch {

  def createContext[F[+_]: Async: Console, Request, Response](
      name: String
  ): F[DispatchContext[F, Request, Response]] =
    Dispatch.createMailbox[F, Request](name).map(DispatchContext(_))

  private def createMailbox[F[+_]: Async: Console, Request](
      name: String
  ): F[Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]]] =
    Mailboxes
      .createMailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]](name)

  case class DispatchContext[F[+_]: Async: Console, Request, Response](
      var mailbox: Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]]
  ) {

    def swapMailbox(
        _mailbox: Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]]
    ): F[Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]]] =
      Async[F]
        .delay {
          val previousMailbox
              : Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Request]] =
            mailbox
          mailbox = _mailbox
          previousMailbox
        }

  }
}

trait Dispatch[F[+_], Request, Response] {
  actorCell: ActorCell[F, Request, Response] =>

  /** Initialise the cell, i.e. setup the mailboxes and supervision. The UID must be reasonably different from the previous UID of a possible actor with the same path.
    */
  def init(sendSupervise: Boolean): F[Unit] =
    for {
      _ <- create(None).recoverWith { case NonFatal(e) =>
        handleInvokeFailure(Nil, e)
      }
      _ <-
        if (sendSupervise) {
          parent.internalActorRef.flatMap(internal =>
            internal.sendSystemMessage(Envelope.system(SystemMessage.Supervise(self)))
          )
        } else asyncF.unit
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

  def ?(fa: Request): F[Response] =
    for {
      deferred <- Deferred[F, Any]
      _ <- sendMessage(
        Envelope(fa, self),
        Option(deferred)
      )
      result <- deferred.get
    } yield result.asInstanceOf[Response]

  override def sendMessage(
      msg: Envelope[F, Any],
      deferred: Option[Deferred[F, Any]]
  ): F[Unit] =
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
