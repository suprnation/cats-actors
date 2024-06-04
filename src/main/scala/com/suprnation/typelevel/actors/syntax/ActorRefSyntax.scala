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

package com.suprnation.typelevel.actors.syntax
import cats.effect.Concurrent
import cats.implicits._
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.{ActorRef, InternalActorRef}

trait ActorRefSyntax {
  final implicit class ActorRefSyntaxOps[F[+_]: Concurrent](actorRef: ActorRef[F]) { self =>
    def cellOp: F[Option[ActorCell[F]]] =
      actorRef match {
        case local: InternalActorRef[F] =>
          local.actorCellRef.get
        case _ => None.pure[F]
      }

    def allChildrenFromThisActor: F[List[ActorRef[F]]] =
      for {
        cell <- actorRef.cell
        children <- cell.children
        result <- children.traverse(c => c.allChildrenFromThisActor).map(_.flatten)
      } yield children ++ result

    def allTrackedChildrenFromThisActor: F[List[ActorRef[F]]] =
      allChildrenFromThisActor.flatMap { children =>
        children
          .traverse { actorRef =>
            (actorRef.cell >>= (_.actor)).map {
              case _: TrackingActor[F] => Some(actorRef)
              case _                   => None
            }
          }
          .map(_.collect { case Some(ref) => ref })
      }

    def withCellDo[A](block: (ActorCell[F]) => F[A]): F[A] =
      actorRef.cell >>= block

    // Tracker
    def preStartCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.preStartCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def postStopCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.postStopCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def preRestartCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.preRestartCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def postRestartCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.postRestartCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def initCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.initCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def preSuspendCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.preSuspendCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def preResumeCount: F[(String, Int)] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.preResumeCount.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> 0).pure[F]
      }

    def messageBuffer: F[(String, List[Any])] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.messageBuffer.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> List.empty[Any]).pure[F]
      }

    def restartMessageBuffer: F[(String, List[Any])] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.restartMessageBuffer.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> List.empty[Any]).pure[F]
      }

    def errorMessageBuffer: F[(String, List[(Throwable, Option[Any])])] =
      actorRef.cell >>= (_.actor) >>= {
        case trackingActor: TrackingActor[F] =>
          trackingActor.errorMessageBuffer.map(this.actorRef.path.name -> _)
        case _ => (this.actorRef.path.name -> List.empty[(Throwable, Option[Any])]).pure[F]
      }

    def cell: F[ActorCell[F]] =
      for {
        cell <- actorRef match {
          case local: InternalActorRef[F] =>
            for {
              cellOp <- local.actorCellRef.get
              cell <- cellOp match {
                case Some(cell) => Concurrent[F].pure(cell)
                case None =>
                  Concurrent[F].raiseError(
                    new IllegalStateException(
                      s"[Actor: ${actorRef.path.name}] is not initialised, cell is not active.  "
                    )
                  )
              }
            } yield cell
          case _ =>
            Concurrent[F].raiseError(
              new IllegalStateException(s"[Actor: ${actorRef.path.name}] is not a local actor.  ")
            )
        }
      } yield cell

  }
}
