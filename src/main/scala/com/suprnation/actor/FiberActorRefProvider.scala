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

import cats.Parallel
import cats.effect.std.{Console, Supervisor}
import cats.effect.{Async, Deferred, Temporal}
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.props.Props

import java.util.UUID

/** Interface by the ActorSystem and ActorContext, the only two places from which you can get a fresh actor.
  */
trait FiberActorRefProvider[F[+_]] {

  /** Create new actor as child of this context and give it an automatically generated name.
    *
    * See [[com.suprnation.actor.props.Props]] for details how to obtain a `Props` object.
    */
  def actorOf(props: => Props[F]): F[InternalActorRef[F]] =
    actorOf(props, UUID.randomUUID().toString)

  /** Creates new actor as a child of this context with the given name which must not be null, empty or start with "$". If the given name is already in use an [[InvalidActorNameException]] is thrown.
    *
    * See [[com.suprnation.actor.props.Props]] for details how to obtain a `Props` object.
    *
    * @throws InvalidActorNameException
    *   if given name is invalid or already in use.
    * @return
    */
  def actorOf(props: => Props[F], name: => String): F[InternalActorRef[F]]
}

/** Interface by the ActorSystem and ActorContext, the only two places from which you can get a fresh actor.
  */
trait ActorRefProvider[F[+_]] {

  /** Create new actor as child of this context and give it an automatically generated name.
    *
    * See [[com.suprnation.actor.props.Props]] for details how to obtain a `Props` object.
    */
  def actorOf(props: => Props[F]): F[ActorRef[F]] = actorOf(props, UUID.randomUUID().toString)

  /** Creates new actor as a child of this context with the given name which must not be null, empty or start with "$". If the given name is already in use an [[InvalidActorNameException]] is thrown.
    *
    * See [[com.suprnation.actor.props.Props]] for details how to obtain a `Props` object.
    *
    * @throws InvalidActorNameException
    *   if given name is invalid or already in use.
    * @return
    */
  def actorOf(props: => Props[F], name: => String): F[ActorRef[F]]
}

class LocalActorRefProvider[F[+_]: Parallel: Async: Temporal: Console](
    supervisor: Supervisor[F],
    systemShutdownSignal: Deferred[F, Unit],
    system: ActorSystem[F],
    parent: InternalActorRef[F]
) extends FiberActorRefProvider[F] {
  override def actorOf(props: => Props[F], name: => String): F[InternalActorRef[F]] = {
    val childPath: ActorPath = new ChildActorPath(parent.path, name, ActorCell.newUid())
    InternalActorRef(supervisor, systemShutdownSignal, name, props, system, childPath, Some(parent))
  }
}
