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

package com.suprnation.actor.scheduler

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.effect.std.CountDownLatch
import cats.effect.implicits._
import com.suprnation.actor.ReplyingActor
import com.suprnation.actor.SupervisorStrategy.{Decider, defaultDecider, Resume}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.sender.Sender.BaseActor.{Ask, BaseActorMessages, Forward, Tell}
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.HashMap
import scala.concurrent.duration._

import java.time.LocalDateTime
import com.suprnation.actor.SupervisionStrategy

object SchedulerSuite {
  sealed trait Message
  case object Tick extends Message
  case class Tack(sender: ActorRef[IO, Message]) extends Message
  case object Tock extends Message

  case object TestException extends Exception("Scheduler test exception")

  val testDecider: Decider = { TestException => Resume }

  class TickActor(latch: CountDownLatch[IO]) extends Actor[IO, Message] {
    override def receive: Receive[IO, Message] = { case Tick =>
      latch.release
    }

  }

  def getTickActor(system: ActorSystem[IO], latch: CountDownLatch[IO]): IO[ActorRef[IO, Message]] =
    for {
      cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
        HashMap.empty[String, TrackingActor.ActorRefs[IO]]
      )
      tickActor <- system.actorOf(new TickActor(latch).track("tick")(cache))
    } yield tickActor

  class ProxyActor(actorRef: ActorRef[IO, Message]) extends Actor[IO, Message] {
    override def receive: Receive[IO, Message] = actorRef >>! _
  }

}

class SchedulerSuite extends AsyncFlatSpec with Matchers {

  import SchedulerSuite._

  implicit class AwaitingCountDownLatch(latch: CountDownLatch[IO]) {

    def await(duration: FiniteDuration): IO[Boolean] =
      latch.await.as(true).timeoutTo(duration, IO.pure(false))
  }

  "Schedule once" should "schedule exactly once" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          latch <- CountDownLatch[IO](3)
          tickActor <- getTickActor(system, latch)

          // run after 300 millisecs
          _ <- system.scheduler.scheduleOnce_(300.millis)(tickActor ! Tick)
          _ <- system.scheduler.scheduleOnce_(300.millis)(latch.release)

          // it should not run immediately
          ranImmediately <- latch.await(100.millis)
          immediateIdle <- system.scheduler.isIdle
          immediateMessages <- tickActor.messageBuffer

          // it should not run more than once
          ranMoreThanOnce <- latch.await(2.seconds)
          idleAfter2secs <- system.scheduler.isIdle
          messagesAfter2secs <- tickActor.messageBuffer

          // it should run once
          _ <- latch.release
          finalIdle <- system.scheduler.isIdle
          didRun <- latch.await(2.seconds)
          errors <- tickActor.errorMessageBuffer

        } yield (
          ranImmediately,
          immediateIdle,
          immediateMessages._2,
          ranMoreThanOnce,
          idleAfter2secs,
          messagesAfter2secs._2,
          didRun,
          finalIdle,
          errors._2
        )
      }
      .unsafeToFuture()
      .map {
        case (
              ranImmediately,
              immediateIdle,
              immediateMessages,
              ranMoreThanOnce,
              idleAfter2secs,
              messagesAfter2secs,
              didRun,
              finalIdle,
              errors
            ) =>
          ranImmediately shouldBe false
          immediateIdle shouldBe false
          immediateMessages shouldBe empty
          ranMoreThanOnce shouldBe false
          idleAfter2secs shouldBe true
          messagesAfter2secs should be(List(Tick))
          didRun shouldBe true
          finalIdle shouldBe true
          errors should have size 0
      }
  }

  it should "handle failure" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          _ <- system.scheduler.scheduleOnce_(20.millis)(IO.raiseError[Unit](TestException))

          _ <- IO.sleep(200.millis)
          idle <- system.scheduler.isIdle
        } yield idle
      }
      .unsafeToFuture()
      .map { case idle =>
        idle shouldBe true
      }
  }

  it should "be cancellable" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Message]("scheduler test ignoring actor")
              .track("scheduler test ignoring actor")(cache)
          )

          // run after 300 millisecs
          fiber <- system.scheduler.scheduleOnce_(300.millis)(testActor ! Tick)
          _ <- fiber.cancel

          _ <- IO.sleep(800.millis)
          idle <- system.scheduler.isIdle
          messages <- testActor.messageBuffer

        } yield (idle, messages._2)
      }
      .unsafeToFuture()
      .map { case (idle, messages) =>
        idle shouldBe true
        messages shouldBe empty
      }
  }

  "Schedule with fixed delay" should "schedule more than once" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          count <- Ref[IO].of(0)
          testActor <- system.actorOf(new Actor[IO, Message] {
            override def receive: Receive[IO, Message] = { case Tack(sender) =>
              count.getAndUpdate(_ + 1).map(_ < 3).ifM(sender ! Tock, IO.unit)
            }
          })

          receiver <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Message]("scheduler test ignoring actor")
              .track("scheduler test ignoring actor")(cache)
          )

          // run every 50 milliseconds
          _ <- system.scheduler.scheduleWithFixedDelay(Duration.Zero, 50.millis)(
            testActor ! Tack(receiver)
          )

          _ <- IO.sleep(500.millis)
          messages <- receiver.messageBuffer
        } yield messages._2
      }
      .unsafeToFuture()
      .map(messages => messages shouldBe (List(Tock, Tock, Tock)))
  }

  it should "handle failure" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          receiver <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Message]("scheduler test ignoring actor")
              .track("scheduler test ignoring actor")(cache)
          )

          count <- Ref[IO].of(0)

          // run every 50 milliseconds
          _ <- system.scheduler.scheduleWithFixedDelay(Duration.Zero, 100.millis) {
            count.getAndUpdate(_ + 1).map(_ < 3).ifM(receiver ! Tock, IO.raiseError(TestException))
          }

          _ <- IO.sleep(500.millis)
          messages <- receiver.messageBuffer
          idle <- system.scheduler.isIdle
        } yield (
          messages._2,
          idle
        )
      }
      .unsafeToFuture()
      .map {
        case (
              messages,
              idle
            ) =>
          messages shouldBe (List(Tock, Tock, Tock))
          idle shouldBe true
      }
  }

  it should "be cancellable" in {
    ActorSystem[IO]("Scheduler Suite")
      .use { system =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          testActor <- system.actorOf(
            ReplyingActor
              .ignoring[IO, Message]("scheduler test ignoring actor")
              .track("scheduler test ignoring actor")(cache)
          )

          // run after 300 millisecs
          fiber <- system.scheduler.scheduleWithFixedDelay(Duration.Zero, 300.millis)(
            testActor ! Tick
          )
          _ <- IO.sleep(50.millis)
          _ <- fiber.cancel

          _ <- IO.sleep(800.millis)
          idle <- system.scheduler.isIdle
          messages <- testActor.messageBuffer

        } yield (idle, messages._2)
      }
      .unsafeToFuture()
      .map { case (idle, messages) =>
        idle shouldBe true
        messages shouldBe (List(Tick))
      }
  }

}
