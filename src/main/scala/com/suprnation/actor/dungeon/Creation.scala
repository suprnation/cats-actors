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
import cats.effect.{Async, Concurrent}
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor._
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.{Debug, Error}
import com.suprnation.actor.utils.Unsafe

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.control.NonFatal

object Creation {
  def createContext[F[+_]: Async, Request, Response]: F[CreationContext[F, Request, Response]] =
    Async[F].pure {
      CreationContext[F, Request, Response](None, mutable.Stack.empty, None, None)
    }

  case class CreationContext[F[+_], Request, Response](
      var actorOp: Option[ReplyingActor[F, Request, Response]],
      var behaviourStack: mutable.Stack[ReplyingReceive[F, Request, Response]],
      var actorContextOp: Option[ActorContext[F, Request, Response]],
      var senderOp: Option[NoSendActorRef[F]]
  ) {
    def actorF: ActorInitalisationError[ReplyingActor[F, Request, Response]] =
      ActorInitalisationError[ReplyingActor[F, Request, Response]](actorOp)
    def actorContextF: ActorInitalisationError[ActorContext[F, Request, Response]] =
      ActorInitalisationError[ActorContext[F, Request, Response]](actorContextOp)
  }

}

trait Creation[F[+_], Request, Response] {
  this: ActorCell[F, Request, Response] =>

  import Creation._

  implicit val creationContext: CreationContext[F, Request, Response]

  def create(failure: Option[ActorInitializationException[F]]): F[Unit] = {
    def failActor: F[Unit] =
      creationContext.actorOp match {
        case None => asyncF.unit
        case Some(_) =>
          clearActorFields(recreate = false) >>
            setFailedFatally() >>
            asyncF.delay { creationContext.actorOp = None }
      }

    failure
      .fold(
        // If we do not have a failure try creating the actor, otherwise throw the failure.
        (
          (newActor >>= ((created: ReplyingActor[F, Request, Response]) =>
            created.aroundPreStart()
          )) >>
            system.settings.DebugLifecycle
              .pure[F]
              .ifM(
                publish(clazz => Debug(self.path.toString, clazz, "started (" + clazz + ")")),
                asyncF.unit
              )
        ).recoverWith { case NonFatal(e) =>
          failActor >> (e match {
            case i: InstantiationException =>
              Concurrent[F].raiseError(
                ActorInitializationException[F](
                  self,
                  """exception during creation, this problem is likely to occur because the class of the Actor you tried to create is either,
               a non-static inner class (in which case make it a static inner class or use Props(new ...) or Props( new Creator ... )
               or is missing an appropriate, reachable no-args constructor.
              """,
                  i.getCause
                )
              )
            case x =>
              val rootCauseMessage =
                Option(rootCauseOf(x).getMessage).getOrElse("No message available")
              Concurrent[F].raiseError(
                throw ActorInitializationException(
                  self,
                  s"exception during creation, root cause message: [$rootCauseMessage]",
                  x
                )
              )
          })

        }
      )(error => Concurrent[F].raiseError(error))

  }

  def newActor: F[ReplyingActor[F, Request, Response]] =
    for {
      actorContext <- asyncF.pure(
        ActorContext.createActorContext[F, Request, Response](
          actorSystem,
          parent,
          self.asInstanceOf[InternalActorRef[F, Request, Response]],
          creationContext
        )
      )

      // Set the context to the ref
      _ <- asyncF.delay {
        creationContext.actorContextOp = Some(actorContext)
      }

      // Create the actor and set the fields on the actor.
      actor <- props.flatMap { actor =>
        Unsafe
          .setActorContext(
            Unsafe.setActorSelf(actor, self),
            actorContext
          )
          .pure[F]
      }

      _ <- actor.init

      // Set the actor.
      _ <- asyncF.delay { creationContext.actorOp = Some(actor) }

      // Update the behaviour stack to include the first receive method from the actor
      _ <- asyncF.delay(creationContext.behaviourStack.push(actor.receive))
    } yield actor

  def supervise(child: NoSendActorRef[F]): F[Unit] =
    isTerminating.ifM(
      asyncF.unit,

      // Supervise is the first thing we get from a new child, so store away the UID for later use
      // in handleFailure.
      initChild(child, lock = true).flatMap {
        case Some(_) =>
          system.settings.DebugLifecycle
            .pure[F]
            .ifM(
              publish(clazz => Debug(self.path.toString, clazz, "now supervising " + child)),
              asyncF.unit
            )
        case None =>
          publish(
            Error(
              self.path.toString,
              _,
              "received Supervise from unregistered child " + child + ", this will not end well"
            )
          )
      }
    )

  /** Method used to invoke a user land message.
    *
    * @param messageHandle
    *   the envelope which contains the message and message.
    * @return
    *   an effect unit
    */
  @inline final def receiveMessage(messageHandle: Envelope[F, Any]): F[Any] =
    for {
      actor <- creationContext.actorF.get
      result <- actor.aroundReceive(
        // Try this...
        creationContext.behaviourStack.top,
        messageHandle.message
      )
    } yield result

  override def become(
      behaviour: ReplyingReceive[F, Request, Response],
      discardOld: Boolean
  ): F[Unit] =
    asyncF.delay {
      if (discardOld) creationContext.behaviourStack.pop()
      creationContext.behaviourStack.push(behaviour)
    }

  override def unbecome: F[Unit] =
    asyncF.delay {
      creationContext.behaviourStack.pop()
    }.void

  @tailrec
  private def rootCauseOf(throwable: Throwable): Throwable =
    if (throwable.getCause != null && throwable.getCause != throwable)
      rootCauseOf(throwable.getCause)
    else
      throwable

}
