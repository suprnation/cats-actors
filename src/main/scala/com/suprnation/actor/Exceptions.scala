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

import com.suprnation.actor.ActorRef.NoSendActorRef

import scala.beans.BeanProperty
import scala.util.control.NoStackTrace

object Exception {
  type Catcher[F[+_], T] = PartialFunction[Throwable, F[T]]
}

class AkkaException(message: String, cause: Throwable) extends RuntimeException(message, cause) {
  def this(msg: String) = this(msg, null)
}

/** ActorKilledException is thrown when an Actor receives the [[Kill]] message
  */
final case class ActorKilledException(message: String)
    extends AkkaException(message)
    with NoStackTrace

/** An InvalidActorNameException is thrown when you try to convert something, usually a String, to an Actor name which doesn't validate.
  */
final case class InvalidActorNameException(message: String) extends AkkaException(message)

/** An ActorInitializationException is thrown when the initialization logic for an Actor fails.
  *
  * There is an extractor which works for ActorInitializationException and its subtypes:
  *
  * {{{
  * ex match {
  *   case ActorInitializationException(actor, message, cause) => ...
  * }
  * }}}
  */
class ActorInitializationException[F[+_]] protected (
    val actor: NoSendActorRef[F],
    val message: String,
    val cause: Throwable
) extends AkkaException(ActorInitializationException.enrichedMessage(actor, message), cause) {}

object ActorInitializationException {
  def apply[F[+_]](
      actor: NoSendActorRef[F],
      message: String,
      cause: Throwable = null
  ): ActorInitializationException[F] =
    new ActorInitializationException(actor, message, cause)

  def apply[F[+_]](message: String): ActorInitializationException[F] =
    new ActorInitializationException(null, message, null)

  private def enrichedMessage[F[+_]](actor: NoSendActorRef[F], message: String): String =
    if (actor == null) message else s"${actor.path}: $message"

}

/** A PreRestartException is thrown when the preRestart() method failed; this exception is not propagated to the supervisor, as it originates from the already failed instance, hence it is only visible as log entry on the event stream.
  *
  * @param actor
  *   is the actor whose preRestart() hook failed
  * @param cause
  *   is the exception thrown by that actor within preRestart()
  * @param originalCause
  *   is the exception which caused the restart in the first place
  * @param messageOption
  *   is the message which was optionally passed into preRestart()
  */
final case class PreRestartException[F[+_]](
    override val actor: NoSendActorRef[F],
    override val cause: Throwable,
    originalCause: Option[Throwable],
    messageOption: Option[Any]
) extends ActorInitializationException[F](
      actor,
      "exception in preRestart(" +
        originalCause.fold("None")(t => t.getClass.toString) + ", " +
        (messageOption match {
          case Some(m: AnyRef) => m.getClass;
          case _               => "None"
        }) +
        ")",
      cause
    )

/** A PostRestartException is thrown when constructor or postRestart() method fails during a restart attempt.
  *
  * @param actor
  *   is the actor whose constructor or postRestart() hook failed
  * @param cause
  *   is the exception thrown by that actor within preRestart()
  * @param originalCause
  *   is the exception which caused the restart in the first place
  */
final case class PostRestartException[F[+_]](
    override val actor: NoSendActorRef[F],
    override val cause: Throwable,
    originalCause: Option[Throwable]
) extends ActorInitializationException[F](
      actor,
      "exception post restart (" + originalCause.fold("null")(t => t.getClass.toString) + ")",
      cause
    )

/** InvalidMessageException is thrown when an invalid message is sent to an Actor; Currently only `null` is an invalid message.
  */
final case class InvalidMessageException private (message: String) extends AkkaException(message)

/** A DeathPactException is thrown by an Actor that receives a Terminated(someActor) message that it doesn't handle itself, effectively crashing the Actor and escalating to the supervisor.
  */
final case class DeathPactException[F[+_]] private (dead: NoSendActorRef[F])
    extends AkkaException("Monitored actor [" + dead + "] terminated")
    with NoStackTrace

/** This message is published to the EventStream whenever an Actor receives a message it doesn't understand
  */
@SerialVersionUID(1L)
final case class UnhandledMessage[F[+_]](
    @BeanProperty message: Any,
    @BeanProperty sender: Option[NoSendActorRef[F]],
    @BeanProperty recipient: NoSendActorRef[F]
)
