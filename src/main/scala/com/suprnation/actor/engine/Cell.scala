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

package com.suprnation.actor.engine

import cats.effect.{Deferred, Fiber, Ref}
import com.suprnation.actor.Actor.Message
import com.suprnation.actor._
import com.suprnation.actor.dungeon.ChildrenContainer
import com.suprnation.actor.props.Props

trait Cell[F[+_]] {

  implicit def receiver: Receiver[F]

  /** The "self" reference which this Cell is attached to.
    */
  implicit def self: ActorRef[F]

  /** The system within which this Cell lives.
    */
  def system: ActorSystem[F]

  /** Start the cell: enqueued message must not be processed before this has been called. The usual action is to attach the mailbox to a dispatcher.
    */
  def start: F[Fiber[F, Throwable, Unit]]

  /** Recursively suspend this actor and all its children. Is only allowed to throw Fatal Throwables.
    */
  def suspend(causeByFailure: Option[Throwable]): F[Unit]

  /** Recursively resume this actor and all its children. Is only allowed to throw Fatal Throwable.
    */
  def resume(causedByFailure: Option[Throwable]): F[Unit]

  /** Restart this actor (will recursively restart or stop all children). Is only allowed to throw Fatal Throwables.
    */
  def restart(cause: Option[Throwable]): F[Unit]

  /** Recursively terminate this actor and all its children. Is only allowed to throw Fatal Throwables.
    */
  def stop: F[Unit]

  /** Returns “true” if the actor is locally known to be terminated, “false” if alive or uncertain.
    */
  def isTerminated: F[Boolean]

  /** The supervisor of this actor.
    */
  def parent: InternalActorRef[F]

  /** All children of this actor
    */
  def childrenRefs: Ref[F, ChildrenContainer[F]]

  /** Enqueue a message to be sent to the actor; may or may not actually schedule the actor to run, depending on which type of cell it is.
    */
  def sendMessage(msg: Envelope[F, Message], deferred: Option[Deferred[F, Any]] = None): F[Unit]

  /** Enqueue a message to be sent to the actor system queue; may or may not actually schedule the actor to run, depending on which type of cell it is.
    */
  def sendSystemMessage(invocation: SystemMessageEnvelope[F]): F[Unit]

  /** If the actor isLocal, returns whether "user messages" are currently queued, “false” otherwise.
    */
  def hasMessages: F[Boolean]

  /** If the actor isLocal, returns the number of "user messages" currently queued, which may be a costly operation, 0 otherwise.
    */
  def numberOfMessages: F[Int]

  /** The props for this actor cell.
    */
  def props: Props[F]

  /** Determines whether this cell is idle or whether it still has messages to process.
    *
    * @return
    *   a boolean value determining whether the cell is idle.
    */
  def isIdle: F[Boolean]
}
