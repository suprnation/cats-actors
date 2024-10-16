package com.suprnation.actor.timers

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref}
import com.suprnation.actor.Actor.{Receive, ReplyingReceive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy.{Restart, Stop}
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.actor.event.Debug
import com.suprnation.actor.timers.TimersTest._
import com.suprnation.actor.{ActorSystem, DeadLetter, OneForOneStrategy, ReplyingActor, SupervisionStrategy, Timers}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class TimerSpec extends AsyncFlatSpec with Matchers {

  it should "schedule a deferred message and send it after delay" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *> IO.sleep(150.millis)
      })

    count shouldEqual 1
  }

  it should "overwrite scheduled actions for the same key" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! StartTimer(KeyA, 100.millis)) *>
          IO.sleep(150.millis)
      })

    count shouldEqual 1
  }

  it should "support multiple timers on different keys" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! StartTimer(KeyB, 100.millis)) *>
          IO.sleep(250.millis)
      })

    count shouldEqual 2
  }

  it should "cancel scheduled timers" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! CancelTimer(KeyA)) *>
          IO.sleep(150.millis)
      })

    count shouldEqual 0
  }

  it should "cancel all timers on restart" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! RestartCommand) *>
          IO.sleep(150.millis)
      })
    count shouldEqual 0
  }

  it should "cancel all timers on stop" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! StopCommand) *>
          IO.sleep(150.millis)
      })
    count shouldEqual 0
  }

  def fixture(testAction: ActorRef[IO, TimerMsg] => IO[Unit]): Int =
    (for {
      count <- Ref.of[IO, Int](0)
      system <-
        ActorSystem[IO](
          "timers",
          {
            case Debug(_, _, DeadLetter(_, _, _)) =>
              IO.println("Dead letter message received. Failing test") >>
                count.set(-1)
            case _ => IO.unit
          }
        ).allocated.map(_._1)
      parentActor <- system.actorOf(ParentActor(count))
      _ <- testAction(parentActor)
      result <- count.get
    } yield result).unsafeRunSync()

}

object TimersTest {
  sealed trait TimerMsg
  case class StartTimer(key: Key, delay: FiniteDuration) extends TimerMsg
  case class CancelTimer(key: Key) extends TimerMsg
  case object RestartCommand extends TimerMsg
  case object StopCommand extends TimerMsg
  case object CounterAdd extends TimerMsg

  case class TestException(msg: String) extends Exception(msg)

  sealed trait Key
  case object KeyA extends Key
  case object KeyB extends Key

  class TimedActor(count: Ref[IO, Int])
    extends ReplyingActor[IO, TimerMsg, Any]
    with Timers[IO, TimerMsg, Key] {
    override val asyncEvidence: Async[IO] = implicitly[Async[IO]]
    override val timerGen: Ref[IO, Int] = Ref.unsafe(0)
    override val timerRef: Ref[IO, Map[Key, TimerSchedulerImpl.StoredTimer[IO]]] = Ref.unsafe(Map.empty)

    override def receive: Receive[IO, TimerMsg] = {
      case StartTimer(key, delay) => timers.startSingleTimer(key, CounterAdd, delay)
      case CancelTimer(key) => timers.cancel(key)
      case CounterAdd => count.update(_ + 1)
      case RestartCommand => IO.raiseError(TestException("restart!"))
      case StopCommand => IO.raiseError(TestException("stop!"))
    }

    override def preRestart(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
      IO.println(s"preRestart with reason: ${reason}, message: ${message}")
  }

  case class ParentActor(count: Ref[IO, Int]) extends ReplyingActor[IO, TimerMsg, Any] {
    val child: Ref[IO, Option[ActorRef[IO, TimerMsg]]] = Ref.unsafe(None)

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy()({
        case TestException("restart!") => Restart
        case TestException("stop!") => Stop
      })

    override def receive: ReplyingReceive[IO, TimerMsg, Any] = {
      case msg =>
        for {
          childRefOpt <- child.get
          ref <- childRefOpt.fold(context.actorOf(new TimedActor(count)))(IO.pure)
          _ <- child.set(Some(ref))
          _ <- ref ! msg
        } yield ()
    }
  }
}
