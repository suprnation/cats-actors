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

import cats.effect.syntax.all._
import cats.effect.{Concurrent, Ref}
import cats.syntax.all._
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor._
import com.suprnation.actor.dispatch.SystemMessage.Failed
import com.suprnation.actor.dungeon.ChildrenContainer.TerminatedChildrenContainer
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.event.{Debug, Error, Logging}
import com.suprnation.typelevel.actors.syntax._

import scala.collection.immutable
import scala.util.control.NonFatal

object FaultHandling {
  def createContext[F[+_]: Concurrent]: F[FaultHandlingContext[F]] =
    Ref.of[F, FailedInfo](NoFailedInfo).map(FaultHandlingContext(_))

  sealed trait FailedInfo

  final case class FailedRef[F[+_]](ref: NoSendActorRef[F]) extends FailedInfo

  /*
   * have we told our supervisor that we Failed() and have not yet heard back?
   * (actually: we might have heard back but not yet acted upon it, in case of
   * a restart with dying children)
   * might well be replaced by ref to a Cancellable in the future (see #2299)
   */
  case class FaultHandlingContext[F[_]](failed: Ref[F, FailedInfo])

  case object NoFailedInfo extends FailedInfo

  case object FailedFatally extends FailedInfo
}

trait FaultHandling[F[+_], Request, Response] {
  this: ActorCell[F, Request, Response] =>

  import FaultHandling._

  implicit val concurrentF: Concurrent[F]
  implicit val faultHandlingContext: FaultHandlingContext[F]

  val terminatedChildrenContainer: ChildrenContainer[F] = TerminatedChildrenContainer[F]()

  /* =================
   * T H E   R U L E S
   * =================
   *
   * Actors can be suspended for two reasons:
   * - they fail
   * - their supervisor gets suspended
   *
   * In particular they are not suspended multiple times because of cascading
   * own failures, i.e. while currentlyFailed() they do not fail again. In case
   * of a restart, failures in constructor/preStart count as new failures.
   */

  /** Do recreate an actor in the response to a failure.
    */
  def faultRecreate(cause: Option[Throwable]): F[Unit] =
    for {
      _ <- creationContext.actorOp.fold(
        for {
          _ <- publish(Error(self.path.toString, _, "changing Recreate into Create after " + cause))
          _ <- faultCreate()
        } yield ()
      ) { actor =>
        childrenRefs.get
          .map(_.isNormal)
          .ifM(
            for {
              _ <- isFailedFatally
                .ifM(
                  concurrentF.unit,
                  actor.aroundPreRestart(cause, currentMessage)
                )
                .recoverWith { case e: PreRestartException[?] =>
                  publish(
                    Error(cause.getOrElse(Error.NoCause), self.path.toString, _, e.getMessage)
                  )
                }
                .guarantee(clearActorFields(true))
              _ <- dispatchContext.mailbox.assert(
                _.isSuspended,
                _ =>
                  s"[Actor: ${actor.self.path.name}] mailbox must be suspended during failed creation (faultRecreate)"
                    .pure[F]
              )
              _ <- setChildrenTerminationReason(ChildrenContainer.Recreation(cause))
                .ifM(().pure, finishRecreate(cause))
            } yield (),
            faultResume(None)
          )
      }
    } yield ()

  /** Do suspend the actor in response to a failure of a parent (i.e. the "recursive suspend" feature).
    */
  def faultSuspend(causedByFailure: Option[Throwable]): F[Unit] =
    suspendNonRecursive(causedByFailure) >> suspendChildren()

  //  _failed eq FailedFatally

  protected def suspendChildren(exceptFor: Set[NoSendActorRef[F]] = Set.empty): F[Unit] =
    for {
      children <- childrenRefs.get
      _ <- children.stats.parTraverse_ {
        case ChildRestartStats(child, _, _) if !(exceptFor contains child) =>
          child.asInstanceOf[InternalActorRef[F, Nothing, Any]].suspend(None)
        case _ => concurrentF.unit
      }
    } yield ()

  def faultResume(causedByFailure: Option[Throwable]): F[Unit] =
    for {
      _ <- creationContext.actorOp.fold(
        publish(
          Error(self.path.toString, _, "changing Resume into Create after " + causedByFailure)
        ) >>
          faultCreate()
      )(a =>
        for {
          fatalFail <- isFailedFatally
          _ <-
            if (fatalFail && causedByFailure != null) {
              publish(
                Error(
                  self.path.toString,
                  _,
                  "changing Resume into Restart after " + causedByFailure
                )
              ) >>
                faultRecreate(causedByFailure)
            } else {
              for {
                perp <- perpetrator
                _ <- resumeNonRecursive.guarantee(
                  Option(causedByFailure).fold(concurrentF.unit)(_ => clearFailed())
                )
                _ <- resumeChildren(causedByFailure, perp)
              } yield ()
            }
        } yield ()
      )
    } yield ()

  def faultCreate(): F[Unit] =
    for {
      _ <- dispatchContext.mailbox.assert(
        _.isSuspended,
        _ => s"mailbox must be suspended during failed creation (faultCreate)".pure[F]
      )
      _ <- perpetrator.assert(
        {
          case Some(p) if p == this.self => true.pure[F]
          case _                         => false.pure[F]
        },
        _ => s"perpetrator does not match current actor".pure[F]
      )
      _ <- cancelReceiveTimeout

      // Stop all children, which will turn childrenRefs into
      // TerminatedChildrenContainer (if there are children)
      _ <- children.flatMap(_.traverse(_.stop))

      _ <- setChildrenTerminationReason(ChildrenContainer.Creation()).ifM(
        concurrentF.unit,
        finishCreate()
      )

    } yield ()

  def finishCreate(): F[Unit] =
    resumeNonRecursive.guarantee(clearFailed())

  /*
   * Terminate the actor with all the queues
   */
  def terminate: F[Unit] = for {
    _ <- cancelReceiveTimeout

    // prevent Deadletter(Terminated) messages
    _ <- unwatchWatchedActor

    // stop all children which will turn childrenRefs into TerminatingChildrenContainer (if there are any)
    _ <- children.flatMap(children => children.parTraverse_(_.stop))

    terminatedAlready <- dispatchContext.mailbox.isClosed

    _ <- setChildrenTerminationReason(ChildrenContainer.Termination).ifM(
      if (terminatedAlready) {
        // If the mailbox was already closed we do not need to call it again.
        concurrentF.unit
      } else {
        for {
          _ <- dispatchContext.mailbox.close
          _ <- suspendNonRecursive(None)
          _ <- setFailed(self)
          _ <-
            if (system.settings.DebugLifecycle) publish(Debug(self.path.toString, _, "stopping"))
            else concurrentF.unit
        } yield ()
      },
      childrenContext.childrenRefCriticalSection.permit.use { _ =>
        childrenContext.childrenRefs.set(terminatedChildrenContainer) >>
          finishTerminate
      }
    )

  } yield ()

  protected def setFailed(perpetrator: NoSendActorRef[F]): F[Unit] =
    faultHandlingContext.failed.update {
      case FailedFatally => FailedFatally
      case _             => FailedRef(perpetrator)
    }

  def handleInvokeFailure(
      childrenNotToSuspend: immutable.Iterable[NoSendActorRef[F]],
      t: Throwable
  ): F[Unit] =
    isFailed
      .ifM(
        concurrentF.unit,
        for {
          _ <- this.actorOp.flatMap {
            case None    => concurrentF.unit
            case Some(a) => a.onError(t, currentMessage)
          }
          _ <- suspendNonRecursive(Some(t))
          skip <- currentMessage match {
            // We have received this message from a failed actor so we want to skip the sender.
            case Some(Envelope(Failed(_, _, _), Some(child), _)) => setFailed(child).as(Set(child))
            case _                                               => setFailed(self).as(Set.empty)
          }
          _ <- suspendChildren(exceptFor = skip ++ childrenNotToSuspend)
          _ <- parent.internalActorRef.flatMap(internal => internal.sendSystemMessage(Envelope.system(Failed(self, t, uid))))
        } yield ()
      )
      .recoverWith { case NonFatal(e) =>
        for {
          _ <- publish(
            Error(
              e,
              self.path.toString,
              _,
              "emergency stop: exception in failure handling for " + t.getClass + Logging
                .stackTraceFor(t)
            )
          )
          _ <- children
            .flatMap(cs => cs.map(child => stop(child)).sequence_.guarantee(finishTerminate))
        } yield ()
      }

  protected def isFailed: F[Boolean] = faultHandlingContext.failed.get.map {
    case _: FailedRef[?] => true
    case _               => false
  }

  protected def isFailedFatally: F[Boolean] =
    faultHandlingContext.failed.get.map {
      case _: FailedFatally.type => true
      case _                     => false
    }

  protected def perpetrator: F[Option[NoSendActorRef[F]]] =
    faultHandlingContext.failed.get.map {
      case FailedRef(ref) => Some(ref.asInstanceOf[NoSendActorRef[F]])
      case _              => None
    }

  protected def clearFailed(): F[Unit] =
    faultHandlingContext.failed.update {
      case FailedRef(_) => NoFailedInfo
      case other        => other
    }

  protected def setFailedFatally(): F[Unit] =
    faultHandlingContext.failed.set(FailedFatally)

  protected def finishRecreate(cause: Option[Throwable]): F[Unit] =
    children >>= (survivors =>
      (for {
        _ <- resumeNonRecursive.guarantee(clearFailed())
        freshActor <- newActor

        _ <- freshActor.aroundPostRestart(cause)

        _ <- system.settings.DebugLifecycle
          .pure[F]
          .ifM(
            publish(Debug(self.path.toString, _, "restarted")),
            concurrentF.unit
          )
        _ <- survivors.parTraverse_(child =>
          child
            .asInstanceOf[InternalActorRef[F, Nothing, Any]]
            .restart(cause)
            .recoverWith { case NonFatal(e) =>
              publish(Error(e, self.path.toString, _, "restarting " + child))
            }
        )
      } yield ())
        .recoverWith { case NonFatal(e) =>
          setFailedFatally() >>
            clearActorFields(recreate = false) >>
            handleInvokeFailure(survivors, PostRestartException[F](self, e, cause))
        }
    )

  protected def handleFailure(f: Failed[F]): F[Unit] =
    for {
      childStatistics <- getChildByRef(f.child)
      _ <- childStatistics match {
        /*
         * only act upon the failure, if it comes from a currently known child;
         * the UID protects against reception of a Failed from a child which was
         * killed in preRestart and re-created in postRestart
         */
        case Some(stats) if stats.uid == f.uid =>
          for {
            a <- actor
            allChildStatistics <- getAllChildStats
            handled <- a.supervisorStrategy.handleFailure(
              a.context,
              f.child,
              f.cause,
              stats,
              allChildStatistics
            )
            _ <- if (handled) concurrentF.unit else Concurrent[F].raiseError(f.cause)
          } yield ()
        case Some(stats) =>
          publish(
            Debug(
              self.path.toString,
              _,
              "dropping Failed(" + f.cause + ") from old child " + f.child + " (uid=" + stats.uid + " != " + f.uid + ")"
            )
          )
        case None =>
          publish(
            Debug(
              self.path.toString,
              _,
              "dropping Failed(" + f.cause + ") from unknown child " + f.child
            )
          )

      }
    } yield ()

  final protected def handleChildTerminated(child: NoSendActorRef[F]): F[Unit] =
    removeChildAndGetStateChange(child) >>= (status =>
      for {
        maybeActor <- actorOp
        c <- children
        context <- this.context
        _ <- maybeActor.fold(concurrentF.unit)(actor =>
          actor.supervisorStrategy.handleChildTerminated(context, child, c).recoverWith {
            case NonFatal(e) =>
              publish(event.Error(e, self.path.toString, _, "handleChildTerminated failed")) >>
                handleInvokeFailure(List.empty, e)
          }
        )
        _ <- status match {
          case Some(ChildrenContainer.Recreation(cause)) => finishRecreate(cause)
          case Some(ChildrenContainer.Creation())        => finishCreate()
          case Some(ChildrenContainer.Termination)       => finishTerminate
          case _                                         => concurrentF.unit
        }
      } yield ()
    )

}
