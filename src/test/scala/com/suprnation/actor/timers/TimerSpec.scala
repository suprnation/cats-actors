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
import com.suprnation.actor.Actor.{Actor, Receive, ReplyingReceive}
import com.suprnation.actor.SupervisorStrategy.{Restart, Stop}
import com.suprnation.actor.Timers.TimerMap
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.debug.TrackingActor.ActorRefs
import com.suprnation.actor.event.Debug
import com.suprnation.actor.test.TestKit
import com.suprnation.actor.timers.TimersTest._
import com.suprnation.actor.{
  ActorSystem,
  DeadLetter,
  OneForOneStrategy,
  ReplyingActor,
  ReplyingActorRef,
  SupervisionStrategy,
  Timers
}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TimerSpec extends AsyncFlatSpec with Matchers with TestKit {

  val startTimerA: StartSingleTimer = StartSingleTimer("KeyA", 150.millis)
  val counterAddA: CounterAdd = CounterAdd("KeyA")
  val cancelTimerA: CancelTimer = CancelTimer("KeyA")

  it should "schedule a single message" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 150.millis)(Seq(startTimerA, counterAddA): _*) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 1
  }

  it should "schedule repeated messages" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        val startFixedDelayTimer = StartFixedDelayTimer("KeyA", 150.millis)

        (parentActor ! startFixedDelayTimer) >>
          expectMsgs(trackerActor, 500.millis)(Seq.fill(3)(counterAddA): _*) >>
          (parentActor ! CancelTimer("KeyA")) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 3
  }

  it should "schedule repeated messages after initial delay" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        val startFixedAfterInitialDelayTimer =
          StartFixedAfterInitialDelayTimer("KeyA", initialDelay = 100.millis, 300.millis)

        (parentActor ! startFixedAfterInitialDelayTimer) >>
          expectMsgs(trackerActor, 750.millis)(
            startFixedAfterInitialDelayTimer +: Seq.fill(3)(counterAddA): _*
          ) >>
          (parentActor ! cancelTimerA) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 3
  }

  it should "do nothing before initial delay has elapsed" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        val startFixedAfterInitialDelayTimer =
          StartFixedAfterInitialDelayTimer("KeyA", initialDelay = 1.second, 100.millis)

        (parentActor ! startFixedAfterInitialDelayTimer) >>
          expectMsgs(trackerActor, 300.millis)(startFixedAfterInitialDelayTimer) >>
          (parentActor ! cancelTimerA) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 0
  }

  it should "replace timers with the same key" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          (parentActor ! startTimerA) >>
          expectMsgs(trackerActor, 300.millis)(Seq.fill(3)(startTimerA) :+ counterAddA: _*) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 1
  }

  it should "support timers with different keys" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        val startTimerB = StartSingleTimer("KeyB", 150.millis)
        (parentActor ! startTimerA) >>
          IO.sleep(50.millis) >>
          (parentActor ! startTimerB) >>
          expectMsgs(trackerActor, 700.millis)(
            Seq(startTimerA, startTimerB, counterAddA, CounterAdd("KeyB")): _*
          ) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 2
  }

  it should "cancel timers" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(actorSystem, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! cancelTimerA) >>
          expectMsgs(trackerActor, 500.millis)(Seq(startTimerA, cancelTimerA): _*) >>
          actorSystem.waitForIdle(maxTimeout = 500.millis).void
      }

    count shouldEqual 0
  }

  it should "cancel all timers on restart" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! RestartCommand) >>
          expectMsgs(trackerActor, 500.millis)(Seq(startTimerA, RestartCommand): _*)
      }

    count shouldEqual 0
  }

  it should "cancel all timers on stop" in {
    val count: Int =
      fixture(TimedActor.apply) { case FixtureParams(_, parentActor, trackerActor) =>
        (parentActor ! startTimerA) >>
          (parentActor ! StopCommand) >>
          expectMsgs(trackerActor, 500.millis)(Seq(startTimerA, StopCommand): _*)
      }

    count shouldEqual 0
  }

  it should "support replying actors" in {
    val count: Int =
      fixture(ReplyingTimedActor.apply) {
        case FixtureParams(actorSystem, parentActor, trackerActor) =>
          for {
            reply <- parentActor ? startTimerA
            _ <- expectMsgs(trackerActor, 500.millis)(Seq(startTimerA, counterAddA): _*)
            _ <- actorSystem.waitForIdle(maxTimeout = 500.millis).void
          } yield reply shouldEqual Ok
      }

    count shouldEqual 1
  }

  private case class FixtureParams[Response](
      actorSystem: ActorSystem[IO],
      parentActor: TimedActorRef[Response],
      trackerActor: TimedActorRef[Response]
  )

  private def fixture[Response](
      createF: (
          Ref[IO, Int],
          Ref[IO, Int],
          Ref[IO, Timers.TimerMap[IO, String]]
      ) => ReplyingActor[IO, TimerMsg, Response]
  )(test: FixtureParams[Any] => IO[Unit]): Int = {

    def deadLetterListener(countRef: Ref[IO, Int]): Any => IO[Unit] = {
      case Debug(_, _, DeadLetter(_, _, _)) =>
        IO.println("DeadLetter received. Failing test") >> countRef.set(-1)
      case _ => IO.unit
    }

    Ref
      .empty[IO, Int]
      .flatMap { countRef =>
        ActorSystem[IO]("timers", deadLetterListener(countRef))
          .use { actorSystem =>
            for {
              timerGenRef <- Timers.initGenRef[IO]
              timersRef <- Timers.initTimersRef[IO, String]
              timedActorRef <- Ref.of[IO, Option[TimedActorRef[Response]]](Option.empty)
              cache <- ActorRefs
                .empty[IO]
                .map(
                  _.copy(
                    timerGenRef = timerGenRef,
                    timersRef = timersRef
                  )
                )
              stableName = UUID.randomUUID().toString
              cacheMap <- Ref.of[IO, Map[String, ActorRefs[IO]]](Map(stableName -> cache))
              parentActor <-
                actorSystem.replyingActorOf(
                  new ParentActor(timedActorRef) {
                    override def create: IO[ReplyingActor[IO, TimerMsg, Response]] =
                      TrackingActor.create[IO, TimerMsg, Response](
                        cache = cacheMap,
                        stableName = stableName,
                        proxy = createF.apply(countRef, timerGenRef, timersRef)
                      )
                  }
                )
              trackerActor <- timedActorRef.get.map(_.get)
              _ <- test(FixtureParams(actorSystem, parentActor, trackerActor))
              count <- countRef.get
            } yield count
          }
      }
      .unsafeRunSync()
  }

}

object TimersTest {
  sealed trait TimerMsg
  case class StartSingleTimer(key: String, delay: FiniteDuration) extends TimerMsg
  case class StartFixedDelayTimer(key: String, delay: FiniteDuration) extends TimerMsg
  case class StartFixedAfterInitialDelayTimer(
      key: String,
      initialDelay: FiniteDuration,
      delay: FiniteDuration
  ) extends TimerMsg
  case class CancelTimer(key: String) extends TimerMsg
  case class CounterAdd(source: String) extends TimerMsg
  case object RestartCommand extends TimerMsg
  case object StopCommand extends TimerMsg

  case object RestartException extends Exception("restart!")
  case object StopException extends Exception("stop!")

  sealed trait TimerResponse
  case object Ok extends TimerResponse
  case object NotOk extends TimerResponse

  case class TimedActor(
      countRef: Ref[IO, Int],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, TimerMap[IO, String]]
  ) extends Actor[IO, TimerMsg]
      with Timers[IO, TimerMsg, Any, String] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]

    override def receive: Receive[IO, TimerMsg] = {
      case StartSingleTimer(key, delay) => timers.startSingleTimer(key, CounterAdd(key), delay)
      case StartFixedDelayTimer(key, delay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), delay)
      case StartFixedAfterInitialDelayTimer(key, initialDelay, delay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), initialDelay, delay)
      case CancelTimer(key) => timers.cancel(key)
      case _: CounterAdd    => countRef.update(_ + 1)
      case RestartCommand   => IO.raiseError(RestartException)
      case StopCommand      => IO.raiseError(StopException)
    }
  }

  case class ReplyingTimedActor(
      countRef: Ref[IO, Int],
      timerGenRef: Ref[IO, Int],
      timersRef: Ref[IO, TimerMap[IO, String]]
  ) extends ReplyingActor[IO, TimerMsg, TimerResponse]
      with Timers[IO, TimerMsg, TimerResponse, String] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]

    override def receive: ReplyingReceive[IO, TimerMsg, TimerResponse] = {
      case StartSingleTimer(key, delay) =>
        timers.startSingleTimer(key, CounterAdd(key), delay).as(Ok)
      case StartFixedDelayTimer(key, delay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), delay).as(Ok)
      case StartFixedAfterInitialDelayTimer(key, initialDelay, delay) =>
        timers.startTimerWithFixedDelay(key, CounterAdd(key), initialDelay, delay).as(Ok)
      case CancelTimer(key) => timers.cancel(key).as(Ok)
      case _: CounterAdd    => countRef.update(_ + 1).as(Ok)
      case RestartCommand   => IO.raiseError(RestartException).as(NotOk)
      case StopCommand      => IO.raiseError(StopException).as(NotOk)
    }
  }

  type TimedActorRef[Response] = ReplyingActorRef[IO, TimerMsg, Response]

  abstract class ParentActor[Response](timedActorRef: Ref[IO, Option[TimedActorRef[Response]]])
      extends ReplyingActor[IO, TimerMsg, Any] {
    override def preStart: IO[Unit] =
      getOrCreateChild >> super.preStart

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy() {
        case RestartException => Restart
        case StopException    => Stop
      }

    override def receive: ReplyingReceive[IO, TimerMsg, Response] = { case msg =>
      getOrCreateChild.flatMap(_ ? msg)
    }

    def getOrCreateChild: IO[TimedActorRef[Response]] =
      for {
        childRefOpt <- timedActorRef.get
        ref <- childRefOpt.fold(context.replyingActorOf(create))(IO.pure)
        _ <- timedActorRef.set(Some(ref))
      } yield ref

    def create: IO[ReplyingActor[IO, TimerMsg, Response]]
  }

}
