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

import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor._

import scala.collection.immutable

trait ChildrenContainer[F[+_]] {
  def add(name: String, stats: ChildRestartStats[F]): ChildrenContainer[F]

  def remove(child: NoSendActorRef[F]): ChildrenContainer[F]

  def getByName(name: String): Option[ChildStats]

  def getByRef(actor: NoSendActorRef[F]): Option[ChildRestartStats[F]]

  def children: immutable.List[NoSendActorRef[F]]

  def stats: immutable.List[ChildRestartStats[F]]

  def shallDie(actor: NoSendActorRef[F]): ChildrenContainer[F]

  def isTerminating: Boolean = false

  def isNormal: Boolean = true

  def reserve(name: String): ChildrenContainer[F]

  def unreserve(name: String): ChildrenContainer[F]
}

object ChildrenContainer {

  def toChildRestartStats[F[+_]](
      c: immutable.HashMap[String, ChildStats]
  ): immutable.List[ChildRestartStats[F]] =
    c.values.collect { case child: ChildRestartStats[?] =>
      child.asInstanceOf[ChildRestartStats[F]]
    }.toList

  sealed trait SuspendReason

  trait WaitingForChildren

  final case class Recreation(cause: Option[Throwable])
      extends SuspendReason
      with WaitingForChildren

  final case class Creation() extends SuspendReason with WaitingForChildren

  case class EmptyChildrenContainer[F[+_]]() extends ChildrenContainer[F] {
    val emptyStats = immutable.HashMap.empty[String, ChildStats]

    override def add(name: String, stats: ChildRestartStats[F]): ChildrenContainer[F] =
      new NormalChildrenContainer(emptyStats.updated(name, stats))

    override def remove(child: NoSendActorRef[F]): ChildrenContainer[F] = this

    override def getByName(name: String): Option[ChildStats] = None

    override def getByRef(actor: NoSendActorRef[F]): Option[ChildRestartStats[F]] = None

    override def children: immutable.List[NoSendActorRef[F]] = List.empty

    override def stats: immutable.List[ChildRestartStats[F]] = List.empty

    override def shallDie(actor: NoSendActorRef[F]): ChildrenContainer[F] = this

    override def reserve(name: String): ChildrenContainer[F] =
      new NormalChildrenContainer(emptyStats.updated(name, ChildNameReserved))

    override def unreserve(name: String): ChildrenContainer[F] = this

    override def toString = "no children"
  }

  /** This is the empty container which is installed after the last child has terminated while stopping; it is necessary to distinguish from the normal empty state while calling handleChildTerminated() for the last time.
    */
  case class TerminatedChildrenContainer[F[+_]]() extends ChildrenContainer[F] {
    val emptyStats = immutable.HashMap.empty[String, ChildStats]

    override def add(name: String, stats: ChildRestartStats[F]): ChildrenContainer[F] = this

    override def isTerminating: Boolean = true

    override def isNormal: Boolean = false

    override def remove(child: NoSendActorRef[F]): ChildrenContainer[F] = this

    override def getByName(name: String): Option[ChildStats] = None

    override def getByRef(actor: NoSendActorRef[F]): Option[ChildRestartStats[F]] = None

    override def children: immutable.List[NoSendActorRef[F]] = List.empty

    override def stats: immutable.List[ChildRestartStats[F]] = List.empty

    override def shallDie(actor: NoSendActorRef[F]): ChildrenContainer[F] = this

    override def reserve(name: String): ChildrenContainer[F] =
      throw new IllegalStateException(
        "cannot reserve actor name '" + name + "': already terminated"
      )

    override def unreserve(name: String): ChildrenContainer[F] = this

    override def toString = "terminated"
  }

  /** Normal children container: we do have at least one child, but none of our children are currently terminating (which is the time period between calling context.stop(child) and processing the ChildTerminated() system message).
    */
  case class NormalChildrenContainer[F[+_]](c: immutable.HashMap[String, ChildStats])
      extends ChildrenContainer[F] {

    override def add(name: String, stats: ChildRestartStats[F]): ChildrenContainer[F] =
      new NormalChildrenContainer(c.updated(name, stats))

    override def remove(child: NoSendActorRef[F]): ChildrenContainer[F] = NormalChildrenContainer(
      c - child.path.name
    )

    override def getByName(name: String): Option[ChildStats] = c.get(name)

    override def getByRef(actor: NoSendActorRef[F]): Option[ChildRestartStats[F]] =
      c.get(actor.path.name) match {
        case c @ Some(crs: ChildRestartStats[?]) if crs.child == actor =>
          c.asInstanceOf[Option[ChildRestartStats[F]]]
        case _ => None
      }

    override def children: immutable.List[NoSendActorRef[F]] =
      if (c.isEmpty) immutable.List.empty[NoSendActorRef[F]] else stats.map(_.child)

    override def stats: immutable.List[ChildRestartStats[F]] =
      if (c.isEmpty) immutable.List.empty[ChildRestartStats[F]] else toChildRestartStats(c)

    override def shallDie(actor: NoSendActorRef[F]): ChildrenContainer[F] =
      TerminatingChildrenContainer(c, Set(actor), UserRequest)

    override def reserve(name: String): ChildrenContainer[F] =
      if (c contains name)
        throw InvalidActorNameException(s"actor name [$name] is not unique!")
      else new NormalChildrenContainer(c.updated(name, ChildNameReserved))

    override def unreserve(name: String): ChildrenContainer[F] = c.get(name) match {
      case Some(ChildNameReserved) => NormalChildrenContainer(c - name)
      case _                       => this
    }

    override def toString =
      if (c.size > 20) c.size.toString + " children"
      else c.mkString("children:\n    ", "\n    ", "")
  }

  /** Waiting state: there are outstanding termination requests (i.e. context.stop(child) was called but the corresponding ChildTerminated() system message has not yet been processed). There could be no specific reason (UserRequested), we could be Restarting or Terminating.
    *
    * Removing the last child which was supposed to be terminating will return a different type of container, depending on whether or not children are left and whether or not the reason was “Terminating”.
    */
  final case class TerminatingChildrenContainer[F[+_]](
      c: immutable.HashMap[String, ChildStats],
      toDie: Set[NoSendActorRef[F]],
      reason: SuspendReason
  ) extends ChildrenContainer[F] {

    override def add(name: String, stats: ChildRestartStats[F]): ChildrenContainer[F] = copy(
      c.updated(name, stats)
    )

    override def remove(child: NoSendActorRef[F]): ChildrenContainer[F] = {
      val t: Set[NoSendActorRef[F]] = toDie - child
      if (t.isEmpty) reason match {
        case Termination => new TerminatedChildrenContainer[F] {}
        case _           => NormalChildrenContainer(c - child.path.name)
      }
      else copy(c - child.path.name, t)
    }

    override def getByName(name: String): Option[ChildStats] = c.get(name)

    override def getByRef(actor: NoSendActorRef[F]): Option[ChildRestartStats[F]] =
      c.get(actor.path.name) match {
        case Some(crs: ChildRestartStats[?]) if crs.child == actor =>
          Option(crs.asInstanceOf[ChildRestartStats[F]])
        case _ => None
      }

    override def children: immutable.List[NoSendActorRef[F]] =
      if (c.isEmpty) immutable.List.empty[NoSendActorRef[F]] else stats.map(_.child)

    override def stats: immutable.List[ChildRestartStats[F]] =
      if (c.isEmpty) immutable.List.empty[ChildRestartStats[F]] else toChildRestartStats(c)

    override def shallDie(actor: NoSendActorRef[F]): ChildrenContainer[F] = copy(toDie = toDie + actor)

    override def reserve(name: String): ChildrenContainer[F] = reason match {
      case Termination =>
        throw new IllegalStateException("cannot reserve actor name '" + name + "': terminating")
      case _ =>
        if (c contains name)
          throw InvalidActorNameException(s"actor name [$name] is not unique!")
        else copy(c = c.updated(name, ChildNameReserved))
    }

    override def unreserve(name: String): ChildrenContainer[F] = c.get(name) match {
      case Some(ChildNameReserved) => copy(c = c - name)
      case _                       => this
    }

    override def isTerminating: Boolean = reason == Termination

    override def isNormal: Boolean = reason == UserRequest

    override def toString: String =
      if (c.size > 20) c.size.toString + " children"
      else c.mkString("children (" + toDie.size + " terminating):\n    ", "\n    ", "\n") + toDie
  }

  case object UserRequest extends SuspendReason

  case object Termination extends SuspendReason

  object NormalChildrenContainer {
    def apply[F[+_]](c: immutable.HashMap[String, ChildStats]): ChildrenContainer[F] =
      if (c.isEmpty) new EmptyChildrenContainer[F] {}
      else new NormalChildrenContainer(c)
  }
}
