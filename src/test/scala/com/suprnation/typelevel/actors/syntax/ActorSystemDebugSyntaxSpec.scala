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

package com.suprnation.typelevel.actors.syntax

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO}
import com.suprnation.actor.{Actor, ActorSystem}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class ActorSystemDebugSyntaxSpec extends AsyncFlatSpec with Matchers {

  it should "wait for schedule to be completed before progressing" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        for {
          deferred <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(300 millis)(deferred.complete(()))
          _ <- actorSystem.waitForIdle(maxTimeout = 500 millis)

          // Race deferred.get (which should complete after scheduler) with a fail
          result <- IO.race(
            deferred.get,
            IO.sleep(50 millis) *> IO(fail("waitForIdle did not wait long enough"))
          )
        } yield result.isLeft should be(true)
      )
      .unsafeToFuture()
  }

  it should "wait for all schedules to be completed before progressing" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        for {
          deferred1 <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(300 millis)(deferred1.complete(()))
          deferred2 <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(200 millis)(deferred2.complete(()))

          _ <- actorSystem.waitForIdle(maxTimeout = 500 millis)

          // Race deferred.get (which should complete after scheduler) with a fail
          result <- IO.race(
            deferred1.get *> deferred2.get,
            IO.sleep(50 millis) *> IO(fail("waitForIdle did not wait long enough"))
          )
        } yield result.isLeft should be(true)
      )
      .unsafeToFuture()
  }

  it should "not wait for any schedules to be completed before progressing" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        for {
          deferred1 <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(500 millis)(deferred1.complete(()))
          deferred2 <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(400 millis)(deferred2.complete(()))

          _ <- actorSystem.waitForIdle(checkSchedulerIdle = false, maxTimeout = 200 millis)

          // IO.sleep should win the race as waitForIdle is configured to ignore schedules
          result <- IO.race(
            IO.sleep(50 millis),
            deferred1.get *> deferred2.get *> IO(
              fail("waitForIdle should not have waited for the scheduler")
            )
          )
        } yield result.isLeft should be(true)
      )
      .unsafeToFuture()
  }

  it should "wait for mailboxes to be empty before progressing" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        for {
          deferred <- Deferred[IO, Unit]
          actorRef <- actorSystem.actorOf(Actor.withReceive[IO, String] { case _ =>
            IO.sleep(300 millis) *> deferred.complete(())
          })
          _ <- actorRef ! "Greetings"

          _ <- actorSystem.waitForIdle(maxTimeout = 500 millis)

          // Race deferred.get (which should complete after receive) with a fail
          result <- IO.race(
            deferred.get,
            IO.sleep(50 millis) *> IO(fail("waitForIdle did not wait long enough"))
          )
        } yield result.isLeft should be(true)
      )
      .unsafeToFuture()
  }

  it should "wait for scheduler and all mailboxes to be empty before progressing" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        for {
          mailboxDeferred <- Deferred[IO, Unit]
          actorRef <- actorSystem.actorOf(Actor.withReceive[IO, String] { case _ =>
            IO.sleep(300 millis) *> mailboxDeferred.complete(())
          })
          _ <- actorRef ! "Greetings"
          _ <- actorRef ! "Greetings again!"

          schedulerDeferred <- Deferred[IO, Unit]
          _ <- actorSystem.scheduler.scheduleOnce_(400 millis)(schedulerDeferred.complete(()))

          // Sequential process 1 includes first + second message: 300ms + 300ms
          // Sequential process 2 includes scheduler: 400ms
          // Minimum time to wait would be 600ms from process 1
          _ <- actorSystem.waitForIdle(maxTimeout = 800 millis)

          // Race mailboxDeferred and schedulerDeferred (which should complete after receive and scheduler) with a fail
          result <- IO.race(
            mailboxDeferred.get *> schedulerDeferred.get,
            IO.sleep(50 millis) *> IO(fail("waitForIdle did not wait long enough"))
          )
        } yield result.isLeft should be(true)
      )
      .unsafeToFuture()
  }

}
