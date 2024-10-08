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

import cats.effect._
import cats.effect.implicits._
import cats.effect.std.{Console, Queue, Supervisor}
import cats.syntax.all._
import cats.{Applicative, Parallel}
import com.suprnation.actor
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.event.DeadLetterListener
import com.suprnation.typelevel.actors.syntax._

import java.util.UUID

trait RootActorCreator[F[+_]] {
  def createRootActor[Request, Response](
      props: => ReplyingActor[F, Request, Response],
      name: String
  )(implicit applicativeEvidence: Applicative[F]): F[ReplyingActorRef[F, Request, Response]] =
    createRootActor[Request, Response](props.pure[F], name)

  def createRootActor[Request, Response](
      props: F[ReplyingActor[F, Request, Response]],
      name: String
  ): F[ReplyingActorRef[F, Request, Response]]
}

object ActorSystem {
  // Concurrent.pure needs to change
  def apply[F[+_]: Console: Async](): Resource[F, ActorSystem[F]] = apply(
    UUID.randomUUID().toString
  )

  def apply[F[+_]: Console: Async](
      systemName: String
  ): Resource[F, ActorSystem[F]] = apply(
    systemName,
    message => Console[F].println(s"[EventBus] => $message")
  )

  def apply[F[+_]: Async: Console](
      systemName: String,
      eventStreamListener: Any => F[Unit]
  ): Resource[F, ActorSystem[F]] =
    for {
      systemError <- Resource.make(Ref.of[F, Option[Throwable]](None))(_.get.flatMap {
        case None    => Sync[F].unit
        case Some(t) => t.raiseError[F, Unit]
      })

      supervisor <- Supervisor[F](await = true)
      _eventStream <- Resource.eval(Queue.unbounded[F, Any])

      rootPath: RootActorPath = actor.RootActorPath(
        Address("kukku", systemName.toLowerCase.replace(" ", "-"))
      )
      systemShutdownHook <- Resource.eval(Deferred[F, Unit])
      deadLetterActorRef <- Resource.eval(Ref.of[F, Option[ActorRef[F, Any]]](None))
      guardianActorRef <- Resource.eval(Ref.of[F, Option[ActorRef[F, Any]]](None))
      terminated <- Resource.eval(Ref[F].of(false))

      actorSystem <- Resource.eval(
        Ref[F]
          .of(0)
          .map(schedulerCount =>
            new ActorSystem[F] with RootActorCreator[F] {

              override def name: String = systemName

              override val temporalF: Temporal[F] = implicitly[Temporal[F]]

              override val consoleF: Console[F] = implicitly[Console[F]]

              override def guardianRef: Ref[F, Option[ActorRef[F, Any]]] = guardianActorRef

              override def /(name: String): ActorPath = rootPath / name

              override def /(names: Iterable[String]): ActorPath = rootPath / names

              override def scheduler: Scheduler[F] = Scheduler[F](schedulerCount)

              override val eventStream: Queue[F, Any] = _eventStream

              override def deadLetters: F[ActorRef[F, Any]] = deadLetterActorRef.get.flatMap {
                case Some(actorRef) => actorRef.pure[F]
                case None =>
                  Concurrent[F].raiseError[ActorRef[F, Any]](
                    new Error(
                      "Please use the appropriate construct to construct the actor system.  "
                    )
                  )
              }

              // Some settings so that we can enable / disable some flags.
              override def settings: Settings = new Settings()

              override def replyingActorOf[Request, Response](
                  props: F[ReplyingActor[F, Request, Response]],
                  name: => String = UUID.randomUUID().toString
              ): F[ReplyingActorRef[F, Request, Response]] = for {
                guardian <- guardian.get
                // Use the cell in the actor to create more children.
                cell <- guardian.cell
                localActorRef <- cell.makeChild(props, name)
              } yield localActorRef

              override def terminate(reason: Option[Throwable] = None): F[Unit] =
                systemError.set(reason) >> systemShutdownHook.complete(()) >> terminated.set(true)

              override def waitForTermination: F[Unit] = systemShutdownHook.get

              override def isTerminated: F[Boolean] =
                terminated.get

              def createRootActor[Request, Response](
                  props: F[ReplyingActor[F, Request, Response]],
                  name: String
              ): F[ReplyingActorRef[F, Request, Response]] = for {
                // For root actors we do not want to supervise.
                localActorRef <-
                  InternalActorRef.apply[F, Request, Response](
                    supervisor,
                    systemShutdownHook,
                    name,
                    props,
                    this,
                    rootPath / name,
                    None,
                    sendSupervise = false
                  )
                _ <- localActorRef.start
              } yield localActorRef
            }
          )
      )

      // Dead letter actor.
      _ <- Resource.eval(
        actorSystem
          .createRootActor[Any, Any](DeadLetterListener[F](), "dead-letter")
          .flatMap(deadLetterActor => deadLetterActorRef.updateAndGet(_ => Some(deadLetterActor)))
      )

      // Guardian actor.
      _ <- Resource.eval(
        actorSystem
          .createRootActor(UserGuardian(), "user")
          .flatMap { guardianActor =>
            guardianActorRef.updateAndGet(_ => Some(guardianActor))
          } >>

          supervisor
            .supervise(_eventStream.take.flatTap(eventStreamListener).foreverM.void)
            .hookToTermination(
              systemShutdownHook,
              supervisor
            )
      )

      // Just in case terminate, if there was a termination before this will be ignore.
      _ <- Resource.onFinalize(actorSystem.terminate(None))

    } yield actorSystem

  // Create the case class so that we have a proper class name in the logs
  private case class UserGuardian[F[+_]: Console: Temporal]() extends Actor[F, Any] {

    override def supervisorStrategy: SupervisionStrategy[F] = TerminateActorSystem(context.system)

    override def receive: ReplyingReceive[F, Any, Unit] = { case _: Terminated[?, ?] =>
      Console[F].println("Oqow! Terminated was not handled! we need to shut down!").void
    }

  }

}

trait ActorSystem[F[+_]] extends ActorRefProvider[F] {

  implicit val temporalF: Temporal[F]
  implicit val consoleF: Console[F]

  /** Create a logger which is thread safe.
    */
  val eventStream: Queue[F, Any]
  private val startTime: Long = System.currentTimeMillis

  def name: String

  def settings: Settings

  def /(name: String): ActorPath

  //  def descendant(names: Iterable[String]): ActorPath = /(names.toSeq)

  def child(child: String): ActorPath = /(child)

  def /(name: Iterable[String]): ActorPath

  //  Main event bus for this actor system, used for example for logging
  // def eventStream: EventStream

  def uptime: Long = (System.currentTimeMillis - startTime) / 1000

  def scheduler: Scheduler[F]

  def deadLetters: F[ActorRef[F, Any]]

  override def toString: String = name

  // The guardian actor all user actors will original from this actor...
  def guardianRef: Ref[F, Option[ActorRef[F, Any]]]

  // The guardian actor all user actors will original from this actor...
  def guardian: ActorInitalisationErrorRef[F, ActorRef[F, Any]] = ActorInitalisationErrorRef(
    guardianRef
  )

  def terminate(reason: Option[Throwable] = None): F[Unit]

  def waitForTermination: F[Unit]

  def isTerminated: F[Boolean]
}
