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

import cats.MonadThrow
import cats.effect.{Async, Concurrent, Temporal}
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.dungeon.Creation.CreationContext

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object ActorContext {
  def createActorContext[F[+_]: Async: Temporal, Request, Response](
      _actorSystem: ActorSystem[F],
      _parent: => NoSendActorRef[F],
      _self: InternalActorRef[F, Request, Response],
      _creationContext: CreationContext[F, Request, Response]
  ): ActorContext[F, Request, Response] = new ActorContext[F, Request, Response] {

    override def self: InternalActorRef[F, Request, Response] = _self

    override def become(
        behaviour: ReplyingReceive[F, Request, Response],
        discardOld: Boolean = true
    ): F[Unit] =
      Temporal[F].delay {
        if (discardOld && _creationContext.behaviourStack.size > 1)
          _creationContext.behaviourStack.pop()
        _creationContext.behaviourStack.push(behaviour)
      }

    override def unbecome: F[Unit] =
      Temporal[F].delay {
        _creationContext.behaviourStack.pop()
      }

    override def children: F[Iterable[NoSendActorRef[F]]] =
      _self.actorCellRef.get.flatMap(_.get.children)

    override def child(name: String): F[Option[NoSendActorRef[F]]] = for {
      cellOp <- _self.actorCellRef.get
      child <- cellOp match {
        case Some(cell) =>
          cell.child(name)
        case None =>
          Concurrent[F].pure(None)
      }
    } yield child

    override def system: ActorSystem[F] = _actorSystem

    override def stop(childActor: NoSendActorRef[F]): F[Unit] =
      if (childActor.path.parent == self.path) {
        for {
          childActorOp <- child(childActor.path.name)
          _ <- childActorOp match {
            case Some(child) => child.stop
            case None        => Concurrent[F].unit
          }
        } yield ()
      } else if (self.path == childActor.path) {
        // Here we will call the parent to handle this..
        _self match {
          case local: InternalActorRef[F, ?, ?] => local.stop
        }
      } else {
        Concurrent[F].raiseError(
          new IllegalArgumentException(
            s"Only direct children of an actor can be stopped through the actor context, " +
              s"but you tried to stop [$childActor] by passing its ActorRef to the `stop`  method. "
          )
        )
      }

    def watch(actorRef: NoSendActorRef[F], onTerminated: Request): F[NoSendActorRef[F]] =
      self match {
        case local: InternalActorRef[F, ?, ?] =>
          local.assertCellActiveAndDo(_.watch(actorRef, onTerminated))
      }

    def unwatch(actorRef: NoSendActorRef[F]): F[NoSendActorRef[F]] =
      self match {
        case local: InternalActorRef[F, ?, ?] =>
          local.assertCellActiveAndDo(_.unwatch(actorRef))
      }

    override def setReceiveTimeout(timeout: FiniteDuration, onTimeout: => Request): F[Unit] =
      self match {
        case local: InternalActorRef[F, ?, ?] =>
          local.assertCellActiveAndDo(_.setReceiveTimeout(timeout, onTimeout))
      }

    override val cancelReceiveTimeout: F[Unit] =
      Temporal[F]
        .delay(self)
        .flatMap((local: InternalActorRef[F, ?, ?]) =>
          local.assertCellActiveAndDo(_.cancelReceiveTimeout)
        )

    override def replyingActorOf[ChildRequest, ChildResponse](
        props: F[ReplyingActor[F, ChildRequest, ChildResponse]],
        name: => String = UUID.randomUUID().toString
    ): F[ReplyingActorRef[F, ChildRequest, ChildResponse]] =
      for {
        cellOp <- _self.actorCellRef.get
        actorRef <- cellOp match {
          case None =>
            MonadThrow[F].raiseError(
              new Exception(s"Cell for [Path: ${_self.path}] is not active.  ")
            )
          case Some(cell) => cell.makeChild(props, name)
        }
      } yield actorRef

    override def sender: Option[ActorRef[F, ?]] = _creationContext.senderOp

    override def parent: NoSendActorRef[F] = _parent
  }
}

/** The actor context - the view of the actor cell from the actor. Exposes contextual information for the actor and the current message.
  *
  * There are several ways to create actors (See Props for details on props)
  *
  * // Scala context.actorOf(props, "name") context.actorOf(props) context.actorOf(Props[MyActor]) context.actorOf(Props(classOf[MyActor], arg1, arg2), "name")
  *
  * Where no name is given explicitly, one will be automatically generated.
  *
  * @tparam F
  *   the computation model to use.
  */
trait MinimalActorContext[F[+_], -Request, +Response] extends ActorRefProvider[F] {
  def self: ReplyingActorRef[F, Request, Response]

  def parent: NoSendActorRef[F]

  def children: F[Iterable[NoSendActorRef[F]]]

  def child(name: String): F[Option[NoSendActorRef[F]]]

  def sender: Option[NoSendActorRef[F]]

  /** Force the child Actor under the given name to terminate after it finishes processing its current message. Nothing happens if the ActorRef is a child that is already stopped.
    */
  def stop(child: NoSendActorRef[F]): F[Unit]

  /** Register for [[Terminated]] notification once the Actor identified by the given [[ReplyingActorRef]] terminates. To clear the termination message, unwatch first.
    */
  def watch(
      actorRef: ActorRef[F, Nothing],
      onTerminated: Request
  ): F[NoSendActorRef[F]]

  /** Unregisters this actor as Monitor for the provided ActorRef.
    *
    * @return
    *   the provided ActorRef
    */
  def unwatch(subject: NoSendActorRef[F]): F[NoSendActorRef[F]]

  /** Defines the inactivity timeout after which the sending of a [[com.suprnation.actor.dungeon.ReceiveTimeout]] message is triggered. When specified, the receive function should be able to handle a [[com.suprnation.actor.dungeon.ReceiveTimeout]] message. 1 millisecond is the minimum supported timeout.
    *
    * Please note that the receive timeout might fire and enqueue the `ReceiveTimeout` message right after another message was enqueued; hence it is '''not guaranteed''' that upon reception of the receive timeout there must have been an idle period beforehand as configured via this method.
    *
    * Once set, the receive timeout stays in effect (i.e. continues firing repeatedly after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
    *
    * *Warning*: This method is not thread-safe and must not be accessed from threads other than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and Future callbacks.
    */
  def setReceiveTimeout(timeout: FiniteDuration, onTimeout: => Request): F[Unit]

  /** Cancel the sending of receive timeout notifications.
    *
    * *Warning*: This method is not thread-safe and must not be accessed from threads other than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] callbacks.
    */
  def cancelReceiveTimeout: F[Unit]

}

/** The actor context - the view of the actor cell from the actor. Exposes contextual information for the actor and the current message.
  *
  * There are several ways to create actors (See Props for details on props)
  *
  * // Scala context.actorOf(props, "name") context.actorOf(props) context.actorOf(Props[MyActor]) context.actorOf(Props(classOf[MyActor], arg1, arg2), "name")
  *
  * Where no name is given explicitly, one will be automatically generated.
  *
  * @tparam F
  *   the computation model to use.
  */
trait ActorContext[F[+_], Request, Response]
    extends MinimalActorContext[F, Request, Response]
    with ActorRefProvider[F] {
  def become(behaviour: ReplyingReceive[F, Request, Response], discardOld: Boolean = true): F[Unit]

  def unbecome: F[Unit]

  def system: ActorSystem[F]

}
