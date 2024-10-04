package com.suprnation.actor.test

import cats.effect.{Async, Concurrent}
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
      Async[F].monotonic.flatMap { now => Async[F].raiseUnless(now < stop)(
        new TimeoutException(s"timeout ${max} expired: $message")
      )}
        .andWait(max min interval)
        .untilM_(p)
    }

  def expectMsgs[F[+_]: Async: Console: Concurrent](actor: ActorRef[F, _], timeout: FiniteDuration = 1.minute)(messages: Any*): F[Unit] = 
    for {
      startQ <- actor.messageBuffer
      _ <- Console[F].println(s"Expecting: $messages in queue: $startQ")
      expectedQ = startQ._2 ++ messages
      _ <- awaitCond(actor.messageBuffer.map(_._2 == expectedQ), timeout, 100.millis, s"expecting messages: $messages")
      // .flatTap(buf => IO.println(s">>> $buf"))
    } yield ()

  def expectNoMsg[F[+_]: Async](actor: ActorRef[F, _], timeout: FiniteDuration = 1.minute): F[Unit] = 
    for {
      startQ <- actor.messageBuffer
      _ <- Async[F].sleep(timeout)
      _ <- actor.messageBuffer.flatMap(
        endQ => Async[F].raiseUnless(endQ == startQ)(
          new Exception(s"message buffer unexpected change - before: $startQ - after: $endQ")
        )
      )
    } yield ()
 
  def within[F[_]: Async, T](min: FiniteDuration, max: FiniteDuration)(f: => F[T]): F[T] = for {
    start <- Async[F].monotonic
    result <- f.timeout(max).adaptError{ case t: TimeoutException => new TimeoutException(s"timeout ${max} expired while executing block") }
    finish <- Async[F].monotonic
    diff = finish - start
    _ <- Async[F].raiseWhen(diff < min)(new Exception(s"block took $diff, should at least have been $min"))
    //_ <- Async[F].raiseWhen(diff > max)(new Exception(s"block took $diff, exceeding $max"))
  } yield result

  def within[F[_]: Async, T](max: FiniteDuration)(f: => F[T]): F[T] = within(0.seconds, max)(f)


}

