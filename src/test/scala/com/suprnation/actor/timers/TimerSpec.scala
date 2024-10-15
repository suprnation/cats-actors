package com.suprnation.actor.timers

import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO, Ref}
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.actor.timers.TimersTest._
import com.suprnation.actor.{ActorSystem, ReplyingActor, Timers}
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
          IO.sleep(150.millis)
      })

    count shouldEqual 2
  }

  it should "cancel the scheduled timer and prevent execution" in {
    val count: Int =
      fixture({ timerActor =>
        (timerActor ! StartTimer(KeyA, 100.millis)) *>
          (timerActor ! CancelTimer(KeyA)) *>
          IO.sleep(150.millis)
      })

    count shouldEqual 0
  }

  // this is ~50% faster than starting up an actor system every time
  private val system = ActorSystem[IO]().allocated.unsafeRunSync()._1

  def fixture(testAction: ActorRef[IO, TimerMsg] => IO[Unit]): Int =
    (for {
      count <- Ref.of[IO, Int](0)
      timerActor <- system.actorOf(IO(new TimedActor(count)))
      _ <- testAction(timerActor)
      result <- count.get
    } yield result).unsafeRunSync()

}

object TimersTest {
  sealed trait TimerMsg
  case class StartTimer(key: Key, delay: FiniteDuration) extends TimerMsg
  case class CancelTimer(key: Key) extends TimerMsg
  case object CounterAdd extends TimerMsg

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
    }

  }

}
