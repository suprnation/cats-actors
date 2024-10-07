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

package com.suprnation.actor.timeout

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor._
import com.suprnation.actor.timeout.Suspension.ConstantFlowActor.{ReceiveTimeout, _}
import com.suprnation.actor.timeout.Suspension.constantFlowActor
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object Suspension {

  def constantFlowActor(
      timeout: FiniteDuration,
      ref: Ref[IO, Int],
      buffer: Ref[IO, List[Int]]
  ): ReplyingActor[IO, ConstantFlowActorMessage, (Int, List[Int])] =
    new ReplyingActor[IO, ConstantFlowActorMessage, (Int, List[Int])] {

      def results: IO[(Int, List[Int])] = for {
        counter <- ref.get
        list <- buffer.get
      } yield (counter, list)

      override def preStart: IO[Unit] = context.setReceiveTimeout(timeout, ReceiveTimeout)

      override def receive: ReplyingReceive[IO, ConstantFlowActorMessage, (Int, List[Int])] = {
        case ReceiveTimeout => ref.updateAndGet(_ + 1) >> results

        case NormalMessage(msg: Int) => buffer.updateAndGet(msg :: _) >> results

        case RescheduleTimeout(f) => context.setReceiveTimeout(f, ReceiveTimeout) >> results

        case CancelTimeout => context.cancelReceiveTimeout >> results

        case Get => results
      }
    }

  object ConstantFlowActor {
    implicit def toNormalMessage(a: Int): NormalMessage = NormalMessage(a)
    sealed trait ConstantFlowActorMessage
    case class NormalMessage(msg: Int) extends ConstantFlowActorMessage
    case class RescheduleTimeout(f: FiniteDuration) extends ConstantFlowActorMessage
    case object CancelTimeout extends ConstantFlowActorMessage
    case object Get extends ConstantFlowActorMessage
    case object ReceiveTimeout extends ConstantFlowActorMessage
  }
}

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class ReceiveTimeoutSpec extends AsyncFlatSpec with Matchers {

  it should "be able to receive a timeout - simple timeout case" in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem").allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.replyingActorOf(
        constantFlowActor(1 second, counter, buffer),
        name = "hello-actor"
      )
      _ <- IO.sleep(
        1 second
      ) // timeout processing is tight with ping event that is emitted every 1s, so let the event some space at the beginning

      result1 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)
    } yield result1).unsafeToFuture().map { case (r1, b1) =>
      r1 should be(1)
      b1 should be(List.empty)

    }
  }

  it should "be able to set a receive timeout" in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem").allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.replyingActorOf(
        constantFlowActor(1 second, counter, buffer),
        name = "hello-actor"
      )
      _ <- IO.sleep(
        0.8 second
      ) // timeout processing is tight with ping event that is emitted every 1s, so let the event some space at the beginning

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result1 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)

      _ <- helloActor ! 3
      _ <- helloActor ! 4
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result2 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)

      _ <- helloActor ?! 5
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result3 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)
    } yield (result1, result2, result3)).unsafeToFuture().map {
      case ((r1, b1), (r2, b2), (r3, b3)) =>
        r1 should be(1)
        b1 should be(List(2, 1))

        r2 should be(2)
        b2 should be(List(4, 3))

        r3 should be(3)
        b3 should be(List(5))
    }
  }

  it should "be able to update a receive timeout in realtime.  " in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.replyingActorOf(
        constantFlowActor(1 millis, counter, buffer),
        name = "hello-actor"
      )

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 millis
      _ <- IO.sleep(9 millis)
      _ <- helloActor ! RescheduleTimeout(30 millis)
      _ <- IO.sleep(28 millis)
      result1 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)
    } yield result1).unsafeToFuture().map { case (r1, b1) =>
      r1 should be(0)
      b1 should be(List(2, 1))
    }
  }

  it should "be able to cancel a receive timeout in realtime.  " in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.replyingActorOf(
        constantFlowActor(1 millis, counter, buffer),
        name = "hello-actor"
      )

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 millis
      _ <- IO.sleep(9 millis)
      _ <- helloActor ! CancelTimeout
      _ <- IO.sleep(28 millis)
      result1 <- helloActor ? Get
      _ <- buffer.update(_ => List.empty)
    } yield result1).unsafeToFuture().map { case (r1, b1) =>
      r1 should be(0)
      b1 should be(List(2, 1))
    }
  }
}
