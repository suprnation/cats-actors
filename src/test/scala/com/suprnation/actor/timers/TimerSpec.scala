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

package com.suprnation.actor.timers

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref}
import com.suprnation.actor.Actor.{Receive, ReplyingReceive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy.{Restart, Stop}
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.actor.dungeon.TimerSchedulerImpl.{StoredTimer, TimerMode}
import com.suprnation.actor.event.Debug
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.timers.TimersTest._
import com.suprnation.actor.{
  ActorSystem,
  DeadLetter,
  OneForOneStrategy,
  ReplyingActor,
  SupervisionStrategy,
  Timers
}
import com.suprnation.typelevel.actors.syntax.ActorSyntaxFOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.ArraySeq
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TimerSpec extends AsyncFlatSpec with Matchers with TestKit {

  val startTimerA: StartTimer = StartTimer(KeyA, 100.millis, TimerMode.Single)
  val counterAddA: CounterAdd = CounterAdd(KeyA)

  it should "schedule a single message" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 150.millis)(counterAddA)
      }

    count shouldEqual 1
  }

  it should "schedule repeated messages" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! StartTimer(KeyA, 150.millis, TimerMode.FixedDelay)) >>
          expectMsgs(trackerActor, 450.millis)(ArraySeq.fill(3)(counterAddA): _*) >>
          (parentActor ! CancelTimer(KeyA))
      }

    count shouldEqual 3
  }

  it should "replace timers with the same key" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 150.millis)(counterAddA)
      }

    count shouldEqual 1
  }

  it should "support timers with different keys" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! StartTimer(KeyB, 150.millis, TimerMode.Single)) >>
          expectMsgs(trackerActor, 200.millis)(counterAddA, CounterAdd(KeyB))
      }

    count shouldEqual 2
  }

  it should "cancel timers" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! CancelTimer(KeyA)) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  it should "cancel all timers on restart" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! RestartCommand) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  it should "cancel all timers on stop" in {
    val count: Int =
      fixture { case FixtureParams(parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! StopCommand) >>
          expectNoMsg(trackerActor, 150.millis)
      }

    count shouldEqual 0
  }

  private case class FixtureParams(
      parentActor: ActorRef[IO, TimerMsg],
      trackerActor: ActorRef[IO, Any]
  )

  private def fixture(test: FixtureParams => IO[Unit]): Int = {

    def deadLetterListener(countRef: Ref[IO, Int]): Any => IO[Unit] = {
      case Debug(_, _, DeadLetter(_, _, _)) =>
        IO.println("DeadLetter received. Failing test") >> countRef.set(-1)
      case _ => IO.unit
    }

    Ref
      .empty[IO, Int]
      .flatMap { countRef =>
        ActorSystem[IO]("timers", deadLetterListener(countRef))
          .use { system =>
            for {
              trackerActor <-
                system.actorOf(
                  ReplyingActor
                    .ignoring[IO, Any]("Timers test tracker actor")
                    .trackWithCache("Timers test tracker actor")
                )
              timerGenRef <- Ref.empty[IO, Int]
              timersRef <- Ref.of[IO, Map[Key, StoredTimer[IO]]](Map.empty)
              timedActorRef <- Ref.of[IO, Option[ActorRef[IO, TimerMsg]]](None)
              parentActor <-
                system.actorOf(
                  ParentActor(countRef, trackerActor, timerGenRef, timersRef, timedActorRef)
                )
              _ <- test(FixtureParams(parentActor, trackerActor))
              count <- countRef.get
            } yield count
          }
      }
      .unsafeRunSync()
  }
}

object TimersTest {
  sealed trait TimerMsg
  case class StartTimer(key: Key, delay: FiniteDuration, mode: TimerMode) extends TimerMsg
  case class CancelTimer(key: Key) extends TimerMsg
  case class CounterAdd(source: Key) extends TimerMsg
  case object RestartCommand extends TimerMsg
  case object StopCommand extends TimerMsg

  case class TestException(msg: String) extends Exception(msg)

  sealed trait Key
  case object KeyA extends Key
  case object KeyB extends Key

  case class TimedActor(
      countRef: Ref[IO, Int],
      trackerActor: ActorRef[IO, Any],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, Map[Key, TimerSchedulerImpl.StoredTimer[IO]]]
  ) extends ReplyingActor[IO, TimerMsg, Any]
      with Timers[IO, TimerMsg, Key] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]

    override def receive: Receive[IO, TimerMsg] = {
      case StartTimer(key, delay, TimerMode.Single) =>
        timers.startSingleTimer(key, CounterAdd(key), delay)
      case StartTimer(key, delay, TimerMode.FixedDelay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), delay)
      case CancelTimer(key) => timers.cancel(key)
      case msg: CounterAdd  => countRef.update(_ + 1) >> (trackerActor ! msg)
      case RestartCommand   => IO.raiseError(TestException("restart!"))
      case StopCommand      => IO.raiseError(TestException("stop!"))
    }
  }

  case class ParentActor(
      countRef: Ref[IO, Int],
      trackerActor: ActorRef[IO, Any],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, Map[Key, TimerSchedulerImpl.StoredTimer[IO]]],
      timedActorRef: Ref[IO, Option[ActorRef[IO, TimerMsg]]]
  ) extends ReplyingActor[IO, TimerMsg, Any] {
    override def preStart: IO[Unit] =
      getOrCreateChild >> super.preStart

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy() {
        case TestException("restart!") => Restart
        case TestException("stop!")    => Stop
      }

    override def receive: ReplyingReceive[IO, TimerMsg, Any] = { case msg =>
      getOrCreateChild.flatMap(_ ! msg)
    }

    def getOrCreateChild: IO[ActorRef[IO, TimerMsg]] =
      for {
        childRefOpt <- timedActorRef.get
        ref <- childRefOpt.fold(
          context.actorOf(TimedActor(countRef, trackerActor, timerGenRef, timersRef))
        )(IO.pure)
        _ <- timedActorRef.set(Some(ref))
      } yield ref
  }
}
