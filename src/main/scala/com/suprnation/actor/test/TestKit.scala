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
import cats.implicits._
import cats.effect.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.typelevel.actors.syntax._

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

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

  def expectMsgs[F[+_]: Async: Console](actor: ActorRef[F, ?], timeout: FiniteDuration = 1.minute)(
      messages: Any*
  ): F[Unit] =
    for {
      startQ <- actor.messageBuffer
      _ <- Console[F].println(s"Expecting: $messages in queue: $startQ")
      expectedQ = startQ._2 ++ messages
      _ <- awaitCond(
        actor.messageBuffer.map(_._2 == expectedQ),
        timeout,
        100.millis,
        s"expecting messages: $messages"
      )
      // .flatTap(buf => IO.println(s">>> $buf"))
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

  def expectTerminated[F[+_]: Async](actor: ActorRef[F, ?]): F[Unit] =
    Async[F].ifM(actor.cell.flatMap(_.isTerminated))(
      Async[F].unit,
      Async[F].raiseError(new Exception("Expected actor to be terminated but was still alive"))
    )

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
    // _ <- Async[F].raiseWhen(diff > max)(new Exception(s"block took $diff, exceeding $max"))
  } yield result

  def within[F[_]: Async, T](max: FiniteDuration)(f: => F[T]): F[T] = within(0.seconds, max)(f)

}
