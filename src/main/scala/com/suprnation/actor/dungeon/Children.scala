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

import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.{Concurrent, Deferred, Ref}
import cats.effect.implicits._
import cats.implicits._
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor._
import com.suprnation.actor.dungeon.Children.ChildrenContext
import com.suprnation.actor.dungeon.ChildrenContainer._
import com.suprnation.actor.engine.ActorCell

import scala.collection.immutable
import scala.util.control.NonFatal

object Children {
  def createContext[F[+_]: Concurrent](
      supervisor: Supervisor[F],
      systemShutdownHook: Deferred[F, Unit]
  ): F[ChildrenContext[F]] =
    (Semaphore[F](1), Ref.of[F, ChildrenContainer[F]](new EmptyChildrenContainer[F] {}))
      .mapN(ChildrenContext(supervisor, systemShutdownHook, _, _))

  case class ChildrenContext[F[+_]](
      supervisor: Supervisor[F],
      systemShutdownHook: Deferred[F, Unit],
      childrenRefCriticalSection: Semaphore[F],
      childrenRefs: Ref[F, ChildrenContainer[F]]
  ) {}
}

trait Children[F[+_], Request, Response] {
  self: ActorCell[F, Request, Response] =>
  implicit val childrenContext: ChildrenContext[F]

  def resumeChildren(causedByFailure: Option[Throwable], perp: Option[NoSendActorRef[F]]): F[Unit] =
    for {
      children <- childrenRefs.get
      _ <- children.stats.parTraverse_ { case ChildRestartStats(child, _, _) =>
        child
          .asInstanceOf[InternalActorRef[F, Nothing, Any]]
          .resume(if (perp == Option(child)) causedByFailure else None)
      }
    } yield ()

  final def stop(actor: ActorRef[F, Nothing]): F[Unit] =
    for {
      children <- childrenRefs.get
      _ <- children
        .getByRef(actor)
        .fold(asyncF.unit)(_ => childrenContext.childrenRefs.update(c => c.shallDie(actor)))
      _ <- actor.asInstanceOf[InternalActorRef[F, Nothing, Any]].stop
    } yield ()

  def children: F[List[NoSendActorRef[F]]] = childrenRefs.get.map(_.children)

  def child(name: String): F[Option[NoSendActorRef[F]]] =
    for {
      children <- childrenRefs.get
    } yield children.getByName(name) match {
      case Some(s: ChildRestartStats[?]) => Some(s.asInstanceOf[ChildRestartStats[F]].child)
      case _                             => None
    }

  /** All children of this actor, including only reserved-names.
    */
  override def childrenRefs: Ref[F, ChildrenContainer[F]] = childrenContext.childrenRefs

  def getChildByRef(ref: NoSendActorRef[F]): F[Option[ChildRestartStats[F]]] = for {
    childContainer <- childrenContext.childrenRefs.get
  } yield childContainer.getByRef(ref)

  def getAllChildStats: F[immutable.List[ChildRestartStats[F]]] = for {
    childContainer <- childrenContext.childrenRefs.get
  } yield childContainer.stats

  def isTerminating: F[Boolean] = for {
    childContainer <- childrenContext.childrenRefs.get
  } yield childContainer.isTerminating

  def setChildrenTerminationReason(reason: ChildrenContainer.SuspendReason): F[Boolean] =
    childrenContext.childrenRefCriticalSection.permit.use { _ =>
      for {
        childContainer <- childrenContext.childrenRefs.get
        reasonSet <- childContainer match {
          case c: ChildrenContainer.TerminatingChildrenContainer[F] =>
            swapChildrenRefs(c.copy(reason = reason)).as(true)
          case _ => false.pure[F]
        }
      } yield reasonSet
    }

  def swapChildrenRefs(newChildren: ChildrenContainer[F]): F[Unit] =
    childrenContext.childrenRefs.set(newChildren).void

  def replyingActorOf[ChildRequest, ChildResponse](
      props: F[ReplyingActor[F, ChildRequest, ChildResponse]],
      name: => String
  ): F[ReplyingActorRef[F, ChildRequest, ChildResponse]] =
    makeChild[ChildRequest, ChildResponse](props, name)

  def makeChild[ChildRequest, ChildResponse](
      props: F[ReplyingActor[F, ChildRequest, ChildResponse]],
      name: String
  ): F[ReplyingActorRef[F, ChildRequest, ChildResponse]] =
    childrenContext.childrenRefCriticalSection.permit.use { _ =>
      for {
        /*
         * in case we are currently terminating, fail external attachChild requests
         * (internal calls cannot happen anyway because we are suspended)
         */
        children <- childrenContext.childrenRefs.get
        child <-
          if (children.isTerminating) {
            Concurrent[F].raiseError(
              new IllegalStateException("cannot create children while terminating or terminated")
            )
          } else {
            (for {
              _ <- swapChildrenRefs(children.reserve(name))
              actorRef <- provider.replyingActorOf[ChildRequest, ChildResponse](
                props,
                name
              ) // Create the actor by delegating to the context.

              // Create the child restart statistics.
              _ <- initChild(actorRef, false)
              // Start the actor
              _ <- actorRef.start
            } yield actorRef).recoverWith { case NonFatal(e) =>
              swapChildrenRefs(children.unreserve(name)) >> Concurrent[F].raiseError(e)
            }
          }

      } yield child
    }

  def initChild(ref: NoSendActorRef[F], lock: Boolean = true): F[Option[ChildRestartStats[F]]] = {
    val computation: F[Option[ChildRestartStats[F]]] = for {
      children <- childrenContext.childrenRefs.get
      stats <- children.getByName(ref.path.name) match {
        case old @ Some(crs: ChildRestartStats[?]) =>
          old.asInstanceOf[Option[ChildRestartStats[F]]].pure[F]
        case Some(ChildNameReserved) =>
          val crs: ChildRestartStats[F] = ChildRestartStats(ref)
          val name: String = ref.path.name
          swapChildrenRefs(children.add(name, crs)).as(Option(crs))
        case None => Option.empty[ChildRestartStats[F]].pure[F]
      }
    } yield stats

    if (lock) childrenContext.childrenRefCriticalSection.permit.use(_ => computation)
    else computation

  }

  def removeChildAndGetStateChange(child: NoSendActorRef[F]): F[Option[SuspendReason]] =
    childrenContext.childrenRefCriticalSection.permit.use { _ =>
      def removeChild(ref: NoSendActorRef[F]): F[ChildrenContainer[F]] =
        childrenContext.childrenRefs.get.flatMap { c =>
          val n = c.remove(ref)
          swapChildrenRefs(n).as(n)
        }

      childrenContext.childrenRefs.get.flatMap {
        case TerminatingChildrenContainer(_, _, reason) =>
          removeChild(child).map {
            case _: TerminatingChildrenContainer[?] => None
            case _                                  => Some(reason)
          }
        case _ =>
          removeChild(child).map(_ => None)
      }
    }

}
