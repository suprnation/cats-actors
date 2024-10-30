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

import cats.effect.kernel.Deferred
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.dispatch.SystemMessage

object Envelope {
  def apply[F[+_], Message](message: Message)(implicit
      receiver: Receiver[F]
  ): Envelope[F, Message] = Envelope(message, None, receiver)
  def apply[F[+_], Message](message: Message, sender: NoSendActorRef[F])(implicit
      receiver: Receiver[F]
  ): Envelope[F, Message] = Envelope(message, Some(sender), receiver)
  def system[F[+_]](invocation: SystemMessage[F])(implicit sender: NoSendActorRef[F]) =
    SystemMessageEnvelope(invocation, Some(sender))
  def systemNoSender[F[+_]](invocation: SystemMessage[F]) = SystemMessageEnvelope(invocation, None)
}

case class Envelope[F[+_], A](
    message: A,
    sender: Option[NoSendActorRef[F]],
    receiver: Receiver[F]
)
case class EnvelopeWithDeferred[F[+_], A](
    envelope: Envelope[F, A],
    deferred: Option[Deferred[F, Either[Throwable, Any]]]
)

case class SystemMessageEnvelope[F[+_]](
    invocation: SystemMessage[F],
    sender: Option[NoSendActorRef[F]]
)
