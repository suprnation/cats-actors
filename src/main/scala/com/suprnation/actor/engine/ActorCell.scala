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

import cats.effect._
import cats.effect.implicits._
import cats.effect.std.{Console, Supervisor}
import cats.syntax.all._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor._
import com.suprnation.actor.dispatch._
import com.suprnation.actor.dungeon.Dispatch.DispatchContext
import com.suprnation.actor.dungeon.ReceiveTimeout
import com.suprnation.actor.event.{Debug, LogEvent}
import com.suprnation.typelevel.actors.syntax._

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.control.NonFatal

object ActorCell {
  final val undefinedUid = 0

  @tailrec
  final def newUid(): Int = {
    val uid: Int = scala.util.Random.nextInt()
    if (uid == undefinedUid) newUid()
    else uid
  }
  def apply[F[+_]: Async: Console, Request, Response](
      supervisor: Supervisor[F],
      systemShutdownSignal: Deferred[F, Unit],
      _self: InternalActorRef[F, Request, Response],
      _props: F[ReplyingActor[F, Request, Response]],
      _actorSystem: ActorSystem[F],
      _parent: Option[NoSendActorRef[F]]
  ): F[ActorCell[F, Request, Response]] = for {
    // Create the shutdown signal to shutdown processing.
    shutdownSignal <- Deferred[F, Unit]
    _childrenContext <- dungeon.Children.createContext[F](supervisor, systemShutdownSignal)
    _dispatchContext <- dungeon.Dispatch.createContext[F, Any, Any](_self.name)
    _faultHandlingContext <- dungeon.FaultHandling.createContext[F]
    _deathWatchContext <- dungeon.DeathWatch.createContext[F, Request]
    _receiveTimeoutContextRef <- Ref[F].of(
      dungeon.ReceiveTimeout.ReceiveTimeoutContext[Request](None, None, None)
    )
    _creationContext <- dungeon.Creation.createContext[F, Request, Response]
  } yield {
    new ActorCell[F, Request, Response] {
      override val props: F[ReplyingActor[F, Request, Response]] = _props
      override val actorSystem: ActorSystem[F] = _actorSystem

      override def context: F[ActorContext[F, Request, Response]] =
        _creationContext.actorContextF.get

      // Implicits
      override val asyncF: Async[F] = implicitly[Async[F]]
      override val consoleF: Console[F] = implicitly[Console[F]]

      // Core Plugin
      override val creationContext: dungeon.Creation.CreationContext[F, Request, Response] =
        _creationContext

      // Other plugins
      override val deathWatchContext: dungeon.DeathWatch.DeathWatchContext[F, Request] =
        _deathWatchContext
      override val childrenContext: dungeon.Children.ChildrenContext[F] = _childrenContext
      override val faultHandlingContext: dungeon.FaultHandling.FaultHandlingContext[F] =
        _faultHandlingContext
      override val dispatchContext: dungeon.Dispatch.DispatchContext[F, Any, Any] =
        _dispatchContext

      override val system: ActorSystem[F] = _actorSystem

      var _currentMessage: Option[com.suprnation.actor.Envelope[F, Any]] =
        Option.empty[com.suprnation.actor.Envelope[F, Any]]

      override def currentMessage: Option[Envelope[F, Any]] = _currentMessage

      var _isIdle: Boolean = true
      val isIdleTrue: F[Unit] = Temporal[F].delay { _isIdle = true }

      val receiveTimeout: ReceiveTimeout[F, Request] =
        new ReceiveTimeout[F, Request](_receiveTimeoutContextRef)

      // We can safely assume that all actors have a parent (we guarantee that the guardian is present! only the guardian does not have a parent because its the parent!)
      @inline override def parent: NoSendActorRef[F] = _parent.get

      @inline override def actor: F[ReplyingActor[F, Request, Response]] =
        creationContext.actorF.get

      val actorOp: F[Option[ReplyingActor[F, Request, Response]]] = Temporal[F].delay {
        creationContext.actorOp
      }

      def systemInvoke(message: SystemMessageEnvelope[F]): F[Unit] = {

        def sendAllToDeadLetters(messages: List[SystemMessageEnvelope[F]]): F[Unit] =
          for {
            actorContext <- creationContext.actorContextF.get
            _ <- messages.traverse_(message => actorContext.system.deadLetters.flatMap(_ ! message))
          } yield ()

        for {
          terminated <- isTerminated
          _ <-
            if (terminated) sendAllToDeadLetters(message :: Nil)
            else
              (message.invocation match {
                case SystemMessage.Ping()       => handlePing
                case f: SystemMessage.Failed[F] => handleFailure(f)
                case SystemMessage.DeathWatchNotification(a, ec, at) =>
                  watchedActorTerminated(a, ec, at)
                case SystemMessage.Create(failure) => create(failure)
                case SystemMessage.Watch(watchee, watcher, onTerminated) =>
                  addWatcher(watchee, watcher, onTerminated.asInstanceOf[Request])
                case SystemMessage.UnWatch(watchee, watcher) => removeWatcher(watchee, watcher)
                case SystemMessage.Recreate(cause)           => faultRecreate(cause)
                case SystemMessage.Suspend(cause)            => faultSuspend(cause)
                case SystemMessage.Resume(inRespToFailure)   => faultResume(inRespToFailure)
                case SystemMessage.Terminate()               => terminate
                case SystemMessage.Supervise(child)          => supervise(child)
                case SystemMessage.NoMessage()               => Concurrent[F].unit
              }).recoverWith { case NonFatal(e) =>
                handleInvokeFailure(Nil, e).map(_ -> false)
              }
        } yield ()
      }

      private def handlePing: F[Any] =
        receiveTimeout.checkTimeout(msg => sendMessage(Envelope(msg), None))

      @inline final private def invoke(messageHandle: Envelope[F, Any]): F[(Any, Boolean)] =
        for {
          _ <- receiveTimeout.markLastMessageTimestamp
          result <- (messageHandle.message match {
            case _: AutoReceivedMessage => autoReceiveMessage(messageHandle).map(_ -> true)
            case _                      => receiveMessage(messageHandle).map(_ -> true)
          }).onError { case NonFatal(e) =>
            handleInvokeFailure(Nil, e).map(_ -> false)
          }
        } yield result

      /** Method to receive system generated messages that are automatically processed. Note that actor messages should receive priority so here we will either create a priority queue or else do two separate queues and merge.
        */
      @inline final def autoReceiveMessage(msg: Envelope[F, Any]): F[Any] =
        for {
          _ <-
            if (_actorSystem.settings.DebugAutoReceive) {
              publish(Debug(_self.path.toString, _, "Received AutoReceiveMessage " + msg))
            } else asyncF.unit
          _ <- msg.message match {
            case t: Terminated[?, ?] => receivedTerminated(t.asInstanceOf[Terminated[F, Request]])
            case Kill                => Concurrent[F].raiseError(ActorKilledException("Kill"))
            case PoisonPill          => stop
            case _ => Concurrent[F].raiseError(new Error("Auto message not handled.  "))
          }
        } yield ()

      override def become(
          behaviour: ReplyingReceive[F, Request, Response],
          discardOld: Boolean
      ): F[Unit] =
        actor.flatMap(_.context.become(behaviour, discardOld))

      override def unbecome: F[Unit] = actor.flatMap(_.context.unbecome)

      private val pingMessage = Envelope.system(SystemMessage.Ping[F]())

      override def start: F[Fiber[F, Throwable, Unit]] =
        supervisor
          .supervise(
            Concurrent[F]
              .race(
                Concurrent[F].race(systemShutdownSignal.get, shutdownSignal.get),
                processMailbox.foreverM
              )
              // If the system is suspended, we do not ping.
              .race(
                (Temporal[F].sleep(1 second) >> dispatchContext.mailbox.deadLockCheck
                  .ifM(sendSystemMessage(pingMessage), ().pure[F])).foreverM
              )
              .void
          )

      @inline final private def processMailbox =
        // Internally if there are message the system will take that message
        dispatchContext.mailbox
          .processMailbox { systemMessage =>
            Temporal[F].delay {
              _isIdle = false
              creationContext.senderOp = None
            } >> systemInvoke(systemMessage).uncancelable.map(_ => _isIdle = true)
          } { case EnvelopeWithDeferred(envelope, deferred) =>
            Temporal[F].delay {
              _isIdle = false
              _currentMessage = envelope.some
              creationContext.senderOp = envelope.sender
            } >>
              invoke(envelope)
                .map { case (result, success) =>
                  if (success) {
                    _currentMessage = None
                    creationContext.senderOp = None
                  }
                  result
                }
                .uncancelable
                .recoverWith { (error: Throwable) =>
                  deferred.fold(isIdleTrue)(d => d.complete(Left(error)).map(_ => _isIdle = true))
                } >>= { result =>
              deferred.fold(isIdleTrue)(d => d.complete(Right(result)).map(_ => _isIdle = true))
            }
          }

      def publish(e: Class[?] => LogEvent): F[Unit] =
        for {
          _ <- creationContext.actorOp match {
            case None =>
              _actorSystem.eventStream.offer(e(clazz(null)))
            case Some(a) =>
              _actorSystem.eventStream.offer(e(clazz(a)))
          }
        } yield ()

      override def provider: FiberActorRefProvider[F] = new LocalActorRefProvider[F](
        supervisor,
        systemShutdownSignal,
        system,
        _self
      )

      override def isIdle: F[Boolean] =
        //          (dispatchContext.mailbox.isIdle >>= ((isIdle: Boolean) => Console[F].println(s"[Mailbox (${self.path.name})]:  $isIdle"))) >>
        //            Console[F].println(s"[Actor (${self.path.name})] : ${_isIdle}") >>
        dispatchContext.mailbox.isIdle &&& Temporal[F].delay(_isIdle)

      override implicit def self: ReplyingActorRef[F, Request, Response] = _self

      implicit def receiver: Receiver[F] = Receiver(_self)

      override def clearActorFields(recreate: Boolean): F[Unit] =
        Temporal[F].delay {
          _currentMessage = None
          creationContext.behaviourStack.clear()
        }

      override def clearFieldsForTermination: F[Unit] =
        (creationContext.actorOp match {
          case Some(actor: ActorConfig) if actor.clearActor =>
            Temporal[F].delay {
              None
            }
          case a @ Some(actor: ActorConfig) if !actor.clearActor =>
            Temporal[F].delay {
              a
            }
          case Some(_: ReplyingActor[F, ?, ?]) =>
            Temporal[F].delay {
              None
            }
          case rest =>
            Temporal[F].delay {
              rest
            }
        }) >>
          Temporal[F].delay {
            creationContext.actorContextOp = None
            _isIdle = true
          }

      override def finishTerminate: F[Unit] =
        creationContext.actorOp
          .fold(asyncF.unit)(_.aroundPostStop())
          .recoverWith { case NonFatal(e) =>
            publish(event.Error(e, self.path.toString, _, e.getMessage))
          }
          .guarantee(
            (for {
              deadLetterMailbox <- mailbox.Mailboxes
                .deadLetterMailbox[F, Any, Any](_actorSystem, this.receiver)
              _ <- dispatchContext
                .swapMailbox(deadLetterMailbox)
                .flatMap(mailbox =>
                  mailbox.close >>
                    (for {
                      _ <- mailbox.cleanup {
                        case Left(systemMessage) =>
                          deadLetterMailbox.systemEnqueue(systemMessage)
                        case Right(msg @ EnvelopeWithDeferred(_, _)) =>
                          deadLetterMailbox.enqueue(msg)
                      }
                    } yield ())
                )
            } yield ()) >>
              // swap mailbox
              shutdownSignal.complete(()) >>
              parent.internalActorRef.flatMap(internal =>
                internal.sendSystemMessage(
                  Envelope.system(
                    SystemMessage.DeathWatchNotification(
                      self,
                      existenceConfirmed = true,
                      addressTerminated = false
                    )
                  )
                )
              ) >>
              tellWatcherWeDied() >>
              unwatchWatchedActor >>
              Concurrent[F].whenA(system.settings.DebugLifecycle)(
                publish(Debug(self.path.toString, _, "stopped"))
              ) >>
              clearActorFields(false) >>
              clearFieldsForTermination
          )

      override def setReceiveTimeout(timeout: FiniteDuration, onTimeout: => Request): F[Unit] =
        receiveTimeout.setReceiveTimeout(timeout, onTimeout)

      override def cancelReceiveTimeout: F[Unit] =
        receiveTimeout.cancelReceiveTimeout

    }
  }
}

/** Actor Cell is the machine that will drive the actor system. It will handle two kinds of messages either system messages or auto messages handled by the user
  */
  // format: off
trait ActorCell[F[+_], Request, Response]
  extends Cell[F, Request, Response]
  with dungeon.Creation[F, Request, Response]
  with dungeon.DeathWatch[F, Request, Response]
  with dungeon.FaultHandling[F, Request, Response]
  with dungeon.Suspension[F, Request, Response]
  with dungeon.Children[F, Request, Response]
  with dungeon.Dispatch[F, Request, Response]
  with ActorRefProvider[F] {
// format: on

  implicit val asyncF: Async[F]
  implicit val consoleF: Console[F]

  val dispatchContext: DispatchContext[F, Any, Any]

  def currentMessage: Option[Envelope[F, Any]]

  def context: F[ActorContext[F, Request, Response]]

  def actorSystem: ActorSystem[F]

  def props: F[ReplyingActor[F, Request, Response]]

  def uid: Int = self.path.uid

  def actor: F[ReplyingActor[F, Request, Response]]

  def actorOp: F[Option[ReplyingActor[F, Request, Response]]]

  def become(behaviour: ReplyingReceive[F, Request, Response], discardOld: Boolean = true): F[Unit]

  def unbecome: F[Unit]

  def publish(e: Class[?] => LogEvent): F[Unit]

  def clazz(o: AnyRef): Class[?] = if (o eq null) this.getClass else o.getClass

  // Reset the current message and the behaviour stack
  def clearActorFields(recreate: Boolean): F[Unit]

  // Completely kill the actor.. set the props to null and the actor reference to null.
  // This should just leave a shell of the actor cell.
  def clearFieldsForTermination: F[Unit]

  // Finish the termination
  def finishTerminate: F[Unit]

  def provider: FiberActorRefProvider[F]

  def system: ActorSystem[F]

  def setReceiveTimeout(timeout: FiniteDuration, onTimeout: => Request): F[Unit]

  def cancelReceiveTimeout: F[Unit]
}
