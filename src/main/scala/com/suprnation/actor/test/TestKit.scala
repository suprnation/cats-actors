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

package com.suprnation.actor.test

import cats.effect.Async
import cats.effect.std.Console
import cats.effect.implicits._
import cats.implicits._
import cats.MonadThrow
import com.suprnation.actor.{Actor, ActorSystem, Behaviour, ReplyingActor, ReplyingActorRef}
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.typelevel.actors.syntax._

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import scala.reflect.{classTag, ClassTag}

trait TestKit {

  def awaitCond[F[_]: Async](
      p: F[Boolean],
      max: FiniteDuration,
      interval: Duration = 100.millis,
      message: String = ""
  ): F[Unit] =
    Async[F].monotonic.map(_ + max) >>= { stop =>
      Async[F].monotonic
        .flatMap { now =>
          Async[F].raiseUnless(now < stop)(
            new TimeoutException(s"timeout $max expired: $message")
          )
        }
        .andWait(max.min(interval))
        .untilM_(p)
    }

  def awaitCondOrElse[F[_]: Async](
      p: F[Boolean],
      max: FiniteDuration,
      interval: Duration = 100.millis
  )(exception: Exception = new TimeoutException(s"timeout $max expired")): F[Unit] =
    Async[F].monotonic.map(_ + max) >>= { stop =>
      Async[F].monotonic
        .flatMap { now =>
          Async[F].raiseUnless(now < stop)(
            exception
          )
        }
        .andWait(max.min(interval))
        .untilM_(p)
    }

  def expectMsgs[F[+_]: Async: Console](actor: ActorRef[F, ?], timeout: FiniteDuration = 1.minute)(
      messages: Any*
  ): F[Unit] =
    expectMsgInternal(
      actor,
      timeout,
      startQ => actor.messageBuffer.map(_._2 == startQ._2 ++ messages),
      messages
    )

  /** Waits and asserts for a set of messages to be received by the provided actor.
    * This method ensures that the actor’s message buffer contains all messages, but it does not assert exclusivity.
    * Additional messages in the buffer will not cause this method to fail.
    */
  def expectMsgSet[F[+_]: Async: Console](
      actor: ActorRef[F, ?],
      timeout: FiniteDuration = 1.minute
  )(
      messages: Any*
  ): F[Unit] =
    expectMsgInternal(
      actor,
      timeout,
      _ => actor.messageBuffer.map(_._2.intersect(messages) == messages),
      messages
    )

  private def expectMsgInternal[F[+_]: Async: Console](
      actor: ActorRef[F, ?],
      timeout: FiniteDuration,
      condition: ((String, Seq[Any])) => F[Boolean],
      messages: Any*
  ): F[Unit] =
    for {
      startQ <- actor.messageBuffer
      _ <- Console[F].println(s"Expecting: $messages in queue: $startQ")
      _ <- awaitCond(
        condition(startQ),
        timeout,
        100.millis,
        s"expecting messages: $messages"
      )
    } yield ()

  def expectMsgPF[F[+_]: Async: Console](actor: ActorRef[F, ?], timeout: FiniteDuration = 1.minute)(
      pF: PartialFunction[Any, Unit]
  ): F[Unit] =
    for {
      _ <- awaitCond(
        actor.messageBuffer.map { case (_, messages) =>
          messages.exists(pF.isDefinedAt)
        },
        timeout,
        100.millis,
        s"partial function did not match any of the received messages"
      )
      _ <- actor.messageBuffer.map { case (_, messages) => messages.collectFirst(pF) }
    } yield ()

  def expectMsgType[F[+_]: Async: Console, T: ClassTag](
      actor: ActorRef[F, ?],
      timeout: FiniteDuration = 1.minute
  ): F[Unit] =
    expectMsgInternal(
      actor,
      timeout,
      startQ =>
        actor.messageBuffer.map {
          _._2.splitAt(startQ._2.length).toList match {
            case List(startQ._2, Seq(m)) if classTag[T].runtimeClass.isInstance(m) => true
            case _                                                                 => false
          }
        },
      s"of type: ${classTag[T].toString}"
    )

  def expectNoMsg[F[+_]: Async](
      actor: ActorRef[F, ?],
      timeout: FiniteDuration = 1.minute
  ): F[Unit] =
    for {
      startQ <- actor.messageBuffer
      _ <- Async[F].sleep(timeout)
      _ <- actor.messageBuffer.flatMap(endQ =>
        Async[F].raiseUnless(endQ == startQ)(
          new Exception(s"message buffer unexpected change - before: $startQ - after: $endQ")
        )
      )
    } yield ()

  /** Expects an ActorRef to be terminated at the current, specific point in time. */
  def expectTerminated[F[+_]: Async](actor: ActorRef[F, ?]): F[Unit] =
    Async[F].ifM(actor.cell.flatMap(_.isTerminated))(
      Async[F].unit,
      Async[F].raiseError(new Exception("Expected actor to be terminated but was still alive"))
    )

  /** Expects an ActorRef to be terminated within a finite duration of time. */
  def awaitTerminated[F[+_]: Async](
      actor: ActorRef[F, ?],
      max: FiniteDuration,
      interval: Duration = 100.millis
  )(
      exception: Exception = new TimeoutException(
        s"Expected actor to be terminated within $max but was still alive"
      )
  ): F[Unit] =
    awaitCondOrElse(
      actor.cell.flatMap(_.isTerminated),
      max,
      interval
    )(exception)

  def receiveWhile[F[+_]: Async: Console, T](actor: ActorRef[F, ?], timeout: FiniteDuration)(
      pF: PartialFunction[Any, T]
  ): F[Seq[T]] =
    for {
      _ <- awaitCond(
        actor.messageBuffer.map { case (_, messages) =>
          !messages.forall(pF.isDefinedAt)
        },
        timeout,
        100.millis,
        s"all received messages match the given partial function"
      ).recoverWith { case _: TimeoutException =>
        Console[F].println(s"Timeout was reached in receiveWhile")
      }

      result <- actor.messageBuffer.map { case (_, messages) =>
        messages.takeWhile(pF.isDefinedAt).map(pF)
      }
    } yield result

  def within[F[_]: Async, T](min: FiniteDuration, max: FiniteDuration)(f: => F[T]): F[T] = for {
    start <- Async[F].monotonic
    result <- f.timeout(max).adaptError { case t: TimeoutException =>
      new TimeoutException(s"timeout $max expired while executing block")
    }
    finish <- Async[F].monotonic
    diff = finish - start
    _ <- Async[F].raiseWhen(diff < min)(
      new Exception(s"block took $diff, should at least have been $min")
    )
  } yield result

  def within[F[_]: Async, T](max: FiniteDuration)(f: => F[T]): F[T] = within(0.seconds, max)(f)

  /** Returns a tracked ActorRef that ignores all messages. */
  def ignoringTestProbe[F[+_]: Async: Console, Request](
      actorSystem: ActorSystem[F],
      name: String
  ): F[ActorRef[F, Request]] =
    for {
      actor <- Actor.ignoring[F, Request].trackWithCache(name)
      ref <- actorSystem.actorOf(actor, name)
    } yield ref

  /** Returns a tracked ActorRef that replies to messages with a given receive function */
  def replyingTestProbe[F[+_]: Async, Request, Response](
      actorSystem: ActorSystem[F],
      name: String
  )(_receive: ReplyingReceive[F, Request, Response]): F[ReplyingActorRef[F, Request, Response]] = {
    val actor: ReplyingActor[F, Request, Response] = new ReplyingActor[F, Request, Response] {
      override def receive: Actor.ReplyingReceive[F, Request, Response] = _receive
    }
    actor.trackWithCache(name) >>= { trackedActor =>
      actorSystem.replyingActorOf(trackedActor, name)
    }
  }

  /** Returns a tracked ActorRef with an empty behaviour, i.e. one that will throw an exception on any message received. */
  def emptyTestProbe[F[+_]: Async, Request, Response](
      actorSystem: ActorSystem[F],
      name: String
  ): F[ReplyingActorRef[F, Request, Response]] =
    replyingTestProbe(actorSystem, name)(Behaviour.emptyBehavior(Async[F]))

}
