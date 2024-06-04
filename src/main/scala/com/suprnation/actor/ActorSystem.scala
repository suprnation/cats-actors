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
import cats.effect._
import cats.effect.std.{Console, Queue, Supervisor}
import cats.syntax.all._
import com.suprnation.actor
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.event.DeadLetterListener
import com.suprnation.actor.props.Props
import com.suprnation.typelevel.actors.syntax._

import java.util.UUID

trait RootActorCreator[F[+_]] {
  def createRootActor(props: Props[F], name: String): F[ActorRef[F]]
}

object ActorSystem {
  // Concurrent.pure needs to change
  def apply[F[+_]: Console: Parallel: Async: Temporal](): Resource[F, ActorSystem[F]] = apply(
    UUID.randomUUID().toString
  )

  def apply[F[+_]: Console: Parallel: Async: Temporal](
      systemName: String
  ): Resource[F, ActorSystem[F]] = apply(
    systemName,
    message => Console[F].println(s"[EventBus] => $message")
  )

  def apply[F[+_]: Parallel: Async: Temporal: Console](
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
      deadLetterActorRef <- Resource.eval(Ref.of[F, Option[ActorRef[F]]](None))
      guardianActorRef <- Resource.eval(Ref.of[F, Option[ActorRef[F]]](None))
      terminated <- Resource.eval(Ref[F].of(false))

      actorSystem <- Resource.eval(
        Ref[F]
          .of(1)
          .map(schedulerCount =>
            new ActorSystem[F] with RootActorCreator[F] {

              override def name: String = systemName

              override val temporalF: Temporal[F] = implicitly[Temporal[F]]

              override val consoleF: Console[F] = implicitly[Console[F]]

              override def guardianRef: Ref[F, Option[ActorRef[F]]] = guardianActorRef

              override def /(name: String): ActorPath = rootPath / name

              override def /(names: Iterable[String]): ActorPath = rootPath / names

              override def scheduler: Scheduler[F] = Scheduler[F](schedulerCount)

              def createRootActor(props: Props[F], name: String): F[ActorRef[F]] = for {
                // For root actors we do not want to supervise.
                localActorRef <-
                  InternalActorRef.apply[F](
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

              override val eventStream: Queue[F, Any] = _eventStream

              override def deadLetters: F[ActorRef[F]] = deadLetterActorRef.get.flatMap {
                case Some(actorRef) => actorRef.pure[F]
                case None =>
                  Concurrent[F].raiseError[ActorRef[F]](
                    new Error(
                      "Please use the appropriate construct to construct the actor system.  "
                    )
                  )
              }

              // Some settings so that we can enable / disable some flags.
              override def settings: Settings = new Settings()

              override def actorOf(props: => Props[F], name: => String): F[ActorRef[F]] = for {
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
            }
          )
      )

      // Dead letter actor.
      _ <- Resource.eval(
        actorSystem
          .createRootActor(Props[F](DeadLetterListener()), "dead-letter")
          .flatMap(deadLetterActor => deadLetterActorRef.updateAndGet(_ => Some(deadLetterActor)))
      )

      // Guardian actor.
      _ <- Resource.eval(
        actorSystem
          .createRootActor(Props[F](UserGuardian()), "user")
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
  private case class UserGuardian[F[+_]: Console: Parallel: Concurrent: Temporal]()
      extends Actor[F] {

    override def supervisorStrategy: SupervisionStrategy[F] = TerminateActorSystem(context.system)

    override def receive: Receive[F] = { case _: Terminated[?] =>
      Console[F].println("Oqow! Terminated was not handled! we need to shut down!")
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

  def deadLetters: F[ActorRef[F]]

  override def toString: String = name

  // The guardian actor all user actors will original from this actor...
  def guardianRef: Ref[F, Option[ActorRef[F]]]

  // The guardian actor all user actors will original from this actor...
  def guardian: ActorInitalisationErrorRef[F, ActorRef[F]] = ActorInitalisationErrorRef(guardianRef)

  def terminate(reason: Option[Throwable] = None): F[Unit]

  def waitForTermination: F[Unit]

  def isTerminated: F[Boolean]
}
