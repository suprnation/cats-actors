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

package com.suprnation.actor.dispatch.mailbox

import cats.effect._
import cats.effect.std.{Console, Semaphore}
import cats.syntax.all._
import com.suprnation.actor.Actor.Message
import com.suprnation.actor._
import com.suprnation.typelevel.actors.syntax._

import java.util
import java.util.concurrent.LinkedTransferQueue
import scala.annotation.tailrec

object Mailboxes {

  def deadLetterMailbox[F[+_]: Console: Async: Temporal](
      actorSystem: ActorSystem[F],
      receiver: Receiver[F]
  ): F[Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Actor.Message]]] = {
    def onDeadLetterMailboxEnqueue(msg: Envelope[F, Actor.Message]): F[Unit] = msg.message match {
      case _: DeadLetter[?] => Async[F].unit // actor subscribing to DeadLetter, drop it
      case _ =>
        val dl: DeadLetter[F] = DeadLetter[F](msg, msg.sender, msg.receiver)
        actorSystem.deadLetters.flatMap((deadLetter: ActorRef[F]) =>
          deadLetter.tell(dl)(msg.sender)
        )
    }

    new Mailbox[F, SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Actor.Message]] {
      var lastReceived: Long = System.currentTimeMillis()

      @inline override def enqueue(msg: EnvelopeWithDeferred[F, Message]): F[Unit] =
        Async[F].delay { lastReceived = System.currentTimeMillis() } >> onDeadLetterMailboxEnqueue(
          msg.envelope
        )

      override def dequeue: F[EnvelopeWithDeferred[F, Message]] =
        Concurrent[F].never[EnvelopeWithDeferred[F, Message]]

      @inline override  val deadLockCheck: F[Boolean] = false.pure[F]

      @inline override val hasMessage: F[Boolean] = false.pure[F]

      @inline override val hasSystemMessage: F[Boolean] = false.pure[F]

      @inline override val numberOfMessages: F[Int] = 0.pure[F]

      override def cleanup(
          onMessage: Either[SystemMessageEnvelope[F], EnvelopeWithDeferred[F, Message]] => F[Unit]
      ): F[Unit] = Async[F].unit

      @inline override def systemEnqueue(message: SystemMessageEnvelope[F]): F[Unit] =
        Async[F].delay { lastReceived = System.currentTimeMillis() } >>
          actorSystem.deadLetters >>= (_ ! DeadLetter[F](
          message.invocation,
          message.sender,
          receiver
        ))

      @inline override val tryDequeue: F[Option[EnvelopeWithDeferred[F, Message]]] = None.pure[F]

      @inline override val suspendCount: F[Int] = 0.pure[F]

      @inline override val isSuspended: F[Boolean] = false.pure[F]

      @inline override val isClosed: F[Boolean] = false.pure[F]

      @inline override val resume: F[Unit] =
        Concurrent[F].raiseError(
          new IllegalStateException("[Resume] on DeadLetterMailbox should not be called. ")
        )

      @inline override val suspend: F[Unit] =
        Concurrent[F].raiseError(
          new IllegalStateException("[Suspend] on DeadLetterMailbox should not be called. ")
        )

      @inline override def drainSystemQueue(
          onMessage: SystemMessageEnvelope[F] => F[Unit]
      ): F[List[SystemMessageEnvelope[F]]] =
        List.empty.pure[F]

      @inline override def processMailbox(onSystemMessage: SystemMessageEnvelope[F] => F[Unit])(
          onUserMessage: EnvelopeWithDeferred[F, Message] => F[Unit]
      ): F[Unit] =
        // We never want to process another message - technically there is no queue here so we want to simply
        // synthetically block until we receive the stop. Soon the shutdown signal will be called anyway which will exit the polling loop.
        Concurrent[F].never[Unit]

      @inline override val close: F[Unit] = Concurrent[F].raiseError(
        new IllegalStateException("[Close] on DeadLetterMailbox should not be called. ")
      )

      @inline override val isIdle: F[Boolean] =
        Async[F].delay(lastReceived + 100 < System.currentTimeMillis())

    }.pure[F]
  }

  def createMailbox[F[+_]: Console: Async, SystemMessage, A](
      name: String
  ): F[Mailbox[F, SystemMessage, A]] = {
    for {
      lock <- Semaphore[F](1)
    } yield {
      new Mailbox[F, SystemMessage, A] {
        var processing: Boolean = false
        var isShutDown: Boolean = false
        val userQueue: util.Queue[A] = new LinkedTransferQueue[A]()
        val systemQueue: util.Queue[SystemMessage] = new LinkedTransferQueue[SystemMessage]()
        var deferred: Deferred[F, Unit] = null
        var systemDeferred: Deferred[F, Unit] = null
        var _flag: Option[Deferred[F, Unit]] = None

        var suspended: Int = 0
        var pendingSuspensions = 0

        @inline final def processBlock[B](block: => F[B]): F[B] =
          Async[F].delay { processing = true } >> block <* Async[F].delay { processing = false }

        @inline def dequeueAll[T](queue: util.Queue[T]): F[Seq[T]] = {
          @tailrec
          def dequeueAllLoop(result: List[T]): List[T] = {
            val v = queue.poll()
            if (v == null) {
              result
            } else {
              dequeueAllLoop(result.prepended(v))
            }
          }
          Sync[F].delay(
            dequeueAllLoop(List.empty).reverse
          ) // Dequeues all elements. Adjust condition if necessary.
        }

        @inline final def tryDequeue[T](queue: util.Queue[T]): F[Option[T]] =
          Sync[F].delay(Option(queue.poll()))

        override def cleanup(onMessage: Either[SystemMessage, A] => F[Unit]): F[Unit] =
          // Perhaps one should ensure that we are closed.
          dequeueAll(systemQueue).flatMap(messages =>
            messages.traverse_(s => onMessage(Left(s)))
          ) >>
            dequeueAll(userQueue).flatMap(messages => messages.traverse_(u => onMessage(Right(u))))

        @inline override def systemEnqueue(invocation: SystemMessage): F[Unit] =
          Async[F].delay {
            systemQueue.add(invocation)
          } >>
            (if (deferred != null) {
               deferred.complete(()).void
             } else Async[F].unit) >>
            lock.permit.use { _ =>
              if (systemDeferred != null) {
                systemDeferred.complete(()).void
              } else Async[F].unit
            }

        @inline override def enqueue(msg: A): F[Unit] =
          Async[F].delay {
            userQueue.add(msg)
          } >> (if (deferred != null) {
                  deferred.complete(()).void
                } else Async[F].unit)

        @inline override val dequeue: F[A] =
          Async[F].delay {
            userQueue.poll()
          }

        @inline override val tryDequeue: F[Option[A]] =
          tryDequeue(userQueue)

        @inline def deadLockCheck: F[Boolean] = true.pure[F]

        @inline override val hasMessage: F[Boolean] =
          Async[F].delay {
            !userQueue.isEmpty
          }

        @inline override val hasSystemMessage: F[Boolean] =
          Async[F].delay {
            !systemQueue.isEmpty
          }

        @inline override val numberOfMessages: F[Int] =
          Async[F].delay {
            userQueue.size
          }

        @inline override val suspendCount: F[Int] =
          Async[F].delay {
            suspended
          }

        @inline override val isSuspended: F[Boolean] =
          Async[F].delay(_flag.isDefined)

        @inline override val isClosed: F[Boolean] = Async[F].delay(isShutDown)

        @inline override val resume: F[Unit] = lock.permit.use { _ =>
          for {
            _ <- Async[F].delay(pendingSuspensions -= 1)
            _ <-
              if (pendingSuspensions <= 0) {
                Async[F].delay(_flag).flatMap {
                  case Some(completable) =>
                    completable.complete(()) >> Async[F].delay {
                      _flag = None; pendingSuspensions = 0
                    }
                  case None => Async[F].unit
                }
              } else Async[F].unit
          } yield ()
        }

        @inline override val suspend: F[Unit] = lock.permit.use { _ =>
          for {
            _ <- Async[F].delay { suspended += 1; pendingSuspensions += 1 }
            _ <- _flag match {
              case Some(d) => Concurrent[F].pure(d)
              case None    => Deferred[F, Unit].flatMap(d => Async[F].delay { _flag = Some(d) })
            }
          } yield ()
        }

        @inline override def drainSystemQueue(
            onMessage: SystemMessage => F[Unit]
        ): F[List[SystemMessage]] = {
          def loop(acc: List[SystemMessage]): F[List[SystemMessage]] =
            tryDequeue(systemQueue).flatMap {
              case Some(message) =>
                onMessage(message) >> loop(message :: acc)
              case None =>
                acc.reverse.pure[F]
            }
          loop(Nil)
        }

        @inline final override def processMailbox(
            @inline onSystemMessage: SystemMessage => F[Unit]
        )(@inline onUserMessage: A => F[Unit]): F[Unit] =
          for {
            _ <- Sync[F]
              .tailRecM[Unit, Unit](())(_ =>
                tryDequeue(systemQueue)
                  .flatMap(_.traverse(sM => processBlock(onSystemMessage(sM))))
                  .map(o => if (o.isDefined) Left(()) else Right(()))
              )
              .flatMap { _ =>
                _flag match {
                  case Some(f) =>
                    // Initialise the system deferred because we want to get notified when a new system message is received.
                    // Here we will use the lock because
                    // 1. This is a special use case when the system is suspended
                    // 2. System message should not have a high throughput requirement.
                    lock.permit.use { _ =>
                      Deferred[F, Unit].map { d => systemDeferred = d; deferred = null }
                    } >> Async[F].race(systemDeferred.get, f.get).void

                  case None =>
                    tryDequeue(userQueue).flatMap(_.traverse(uM => processBlock(onUserMessage(uM))))
                }
              }
            _ <- Sync[F]
              .delay(systemQueue.isEmpty && userQueue.isEmpty)
              .ifM(
                // Note that here we run lock free, this is because we are guaranteed to receive the ping in this scenario
                // so even though we might have missed this update we will get it in the next ping.
                Deferred[F, Unit].map(d => deferred = d) >> deferred.get,
                Sync[F].unit
              )
          } yield ()

        @inline override val isIdle: F[Boolean] =
          (hasSystemMessage ||| hasMessage).map(!_).orElse((!processing).pure[F])

        val close: F[Unit] =
          (if (deferred != null) {
             deferred.complete(()).void
           } else Sync[F].unit) >> Async[F].delay {
            isShutDown = true
          }
      }
    }
  }

}
