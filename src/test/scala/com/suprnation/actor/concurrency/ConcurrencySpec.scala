package com.suprnation.actor.concurrency

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.concurrency.ConcurrencySpec.ConcurrentActorTest
import com.suprnation.actor.props.Props
import com.suprnation.actor.ref.UnsafeRef
import com.suprnation.actor.{Actor, ActorSystem}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object ConcurrencySpec {

  class ConcurrentActorTest(
      incrA: UnsafeRef[IO, Int],
      incrB: UnsafeRef[IO, Int],
      incrC: UnsafeRef[IO, Int]
  ) extends Actor[IO] {

    override def receive: Receive[IO] = {
      case "a" => incrA.update(_ + 1)
      case "b" => incrB.update(_ + 1)
      case "c" => incrC.update(_ + 1)
    }
  }
}

class ConcurrencySpec extends AsyncFlatSpec with Matchers {

  it should "allow multiple messages from different actors and these should not be lost.  " in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      incrA <- UnsafeRef.of[IO, Int](0)
      incrB <- UnsafeRef.of[IO, Int](0)
      incrC <- UnsafeRef.of[IO, Int](0)

      calculator <- system.actorOf(Props[IO](new ConcurrentActorTest(incrA, incrB, incrC)))
      _ <- system.actorOf(Props[IO](new Actor[IO] {
        override def preStart: IO[Unit] =
          (calculator ! "a").replicateA_(10000).start.void
      }))
      _ <- system.actorOf(Props[IO](new Actor[IO] {
        override def preStart: IO[Unit] =
          (calculator ! "b").replicateA_(10000).start.void
      }))
      _ <- system.actorOf(Props[IO](new Actor[IO] {
        override def preStart: IO[Unit] =
          (calculator ! "c").replicateA_(10000).start.void
      }))
      _ <- system.waitForIdle()
      a <- incrA.get
      b <- incrB.get
      c <- incrC.get

    } yield (a, b, c)).unsafeToFuture().map { case (a, b, c) =>
      a should be(10000)
      b should be(10000)
      c should be(10000)
    }
  }
}
