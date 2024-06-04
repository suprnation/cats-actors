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
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.dungeon.Creation.CreationContext
import com.suprnation.actor.props.Props

import scala.concurrent.duration.FiniteDuration

object ActorContext {
  def createActorContext[F[+_]: Async: Temporal](
      _actorSystem: ActorSystem[F],
      _parent: => ActorRef[F],
      _self: InternalActorRef[F],
      _creationContext: CreationContext[F]
  ): ActorContext[F] = new ActorContext[F] {

    override def self: InternalActorRef[F] = _self

    override def become(behaviour: Receive[F], discardOld: Boolean = true): F[Unit] =
      Temporal[F].delay {
        if (discardOld) _creationContext.behaviourStack.pop()
        _creationContext.behaviourStack.push(behaviour)
      }

    override def unbecome: F[Unit] =
      Temporal[F].delay {
        _creationContext.behaviourStack.pop()
      }

    override def children: F[Iterable[ActorRef[F]]] = _self.actorCellRef.get.flatMap(_.get.children)

    override def child(name: String): F[Option[ActorRef[F]]] = for {
      cellOp <- _self.actorCellRef.get
      child <- cellOp match {
        case Some(cell) =>
          cell.child(name)
        case None =>
          Concurrent[F].pure(None)
      }
    } yield child

    override def system: ActorSystem[F] = _actorSystem

    override def stop(childActor: ActorRef[F]): F[Unit] =
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
          case local: InternalActorRef[F] => local.stop
          case unexpected =>
            MonadThrow[F].raiseError(
              new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
            )
        }
      } else {
        Concurrent[F].raiseError(
          new IllegalArgumentException(
            s"Only direct children of an actor can be stopped through the actor context, " +
              s"but you tried to stop [$childActor] by passing its ActorRef to the `stop`  method. "
          )
        )
      }

    def watch(actorRef: ActorRef[F]): F[ActorRef[F]] =
      self match {
        case local: InternalActorRef[F] =>
          local.assertCellActiveAndDo(_.watch(actorRef))
        case unexpected =>
          MonadThrow[F].raiseError(
            new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
          ) // Done for completeness
      }

    def unwatch(actorRef: ActorRef[F]): F[ActorRef[F]] =
      self match {
        case local: InternalActorRef[F] =>
          local.assertCellActiveAndDo(_.unwatch(actorRef))
        case unexpected =>
          MonadThrow[F].raiseError(
            new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
          ) // Done for completeness
      }

    override def setReceiveTimeout(timeout: FiniteDuration): F[Unit] =
      self match {
        case local: InternalActorRef[F] =>
          local.assertCellActiveAndDo(_.setReceiveTimeout(timeout))
        case unexpected =>
          MonadThrow[F].raiseError(
            new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
          ) // Done for completeness
      }

    override val cancelReceiveTimeout: F[Unit] =
      Temporal[F].delay(self).flatMap {
        case local: InternalActorRef[F] =>
          local.assertCellActiveAndDo(_.cancelReceiveTimeout)
        case unexpected =>
          MonadThrow[F].raiseError(
            new IllegalArgumentException(s"ActorRef is not internal: $unexpected")
          ) // Done for completeness
      }

    override def actorOf(props: => Props[F], name: => String): F[ActorRef[F]] =
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

    override def sender: Option[ActorRef[F]] = _creationContext.senderOp

    override def parent: ActorRef[F] = _parent
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
trait ActorContext[F[+_]] extends ActorRefProvider[F] {
  def self: ActorRef[F]

  def parent: ActorRef[F]

  def become(behaviour: Receive[F], discardOld: Boolean = true): F[Unit]

  def unbecome: F[Unit]

  def children: F[Iterable[ActorRef[F]]]

  def child(name: String): F[Option[ActorRef[F]]]

  def system: ActorSystem[F]

  def sender: Option[ActorRef[F]]

  /** Force the child Actor under the given name to terminate after it finishes processing its current message. Nothing happens if the ActorRef is a child that is already stopped.
    */
  def stop(child: ActorRef[F]): F[Unit]

  /** Register for [[Terminated]] notification once the Actor identified by the given [[ActorRef]] terminates. To clear the termination message, unwatch first.
    */
  def watch(actorRef: ActorRef[F]): F[ActorRef[F]]

  /** Unregisters this actor as Monitor for the provided ActorRef.
    *
    * @return
    *   the provided ActorRef
    */
  def unwatch(subject: ActorRef[F]): F[ActorRef[F]]

  /** Defines the inactivity timeout after which the sending of a [[ReceiveTimeout]] message is triggered. When specified, the receive function should be able to handle a [[ReceiveTimeout]] message. 1 millisecond is the minimum supported timeout.
    *
    * Please note that the receive timeout might fire and enqueue the `ReceiveTimeout` message right after another message was enqueued; hence it is '''not guaranteed''' that upon reception of the receive timeout there must have been an idle period beforehand as configured via this method.
    *
    * Once set, the receive timeout stays in effect (i.e. continues firing repeatedly after inactivity periods). Pass in `Duration.Undefined` to switch off this feature.
    *
    * *Warning*: This method is not thread-safe and must not be accessed from threads other than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] and Future callbacks.
    */
  def setReceiveTimeout(timeout: FiniteDuration): F[Unit]

  /** Cancel the sending of receive timeout notifications.
    *
    * *Warning*: This method is not thread-safe and must not be accessed from threads other than the ordinary actor message processing thread, such as [[java.util.concurrent.CompletionStage]] callbacks.
    */
  def cancelReceiveTimeout: F[Unit]

}
