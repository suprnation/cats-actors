package com.suprnation.actor.wait

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.props.Props
import com.suprnation.actor.{Actor, ActorRef, ActorSystem}
import com.suprnation.typelevel.actors.implicits._
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object WaitSpecSuite {

  case class SlowActor(ref: Ref[IO, Boolean]) extends Actor[IO] {
    override def receive: Actor.Receive[IO] = { case msg =>
      IO.sleep(10 millisecond) >> ref.set(true).as(msg)
    }
  }

  case class SlowActorWithForward(ref: Ref[IO, Boolean], levels: Int = 1) extends Actor[IO] {
    val childRef: Ref[IO, Option[ActorRef[IO]]] = Ref.unsafe[IO, Option[ActorRef[IO]]](None)

    override def receive: Actor.Receive[IO] =
      if (levels == 1) {
        case "slow" =>
          IO.sleep(10 millisecond) >> ref.set(true)
        case "schedule" =>
          IO.sleep(10 millisecond) >> ref.set(true)
      }
      else {
        case "slow" =>
          for {
            slowActor <- context.actorOfWithDebug(
              Props[IO](SlowActorWithForward(ref, levels - 1)),
              s"${self.path.name.split("-").head}-${levels - 1}"
            )
            _ <- slowActor ! "slow"
          } yield ()
        case "schedule" =>
          for {
            slowActor <- context.actorOfWithDebug(
              Props[IO](SlowActorWithForward(ref, levels - 1)),
              s"${self.path.name.split("-").head}-${levels - 1}"
            )
            _ <- slowActor ! "schedule"
          } yield ()

      }
  }
}

class WaitSpecSuite extends AsyncFlatSpec with Matchers {

  it should "be able to wait for messages to be processed" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOf(Props[IO](WaitSpecSuite.SlowActor(ref)), "waiting")
      _ <- (input ! "slow").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }

  }

  it should "be able to wait for messages to be processed when it has scheduled messages" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOf(Props[IO](WaitSpecSuite.SlowActor(ref)), "waiting")
      _ <- (input ! "schedule").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed (debugger)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(Props[IO](WaitSpecSuite.SlowActor(ref)), "waiting")
      _ <- (input ! "slow").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed when it has scheduled messages (debugger)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(Props[IO](WaitSpecSuite.SlowActor(ref)), "waiting")
      _ <- (input ! "schedule").start
      _ <- input.waitForIdle
      result <- ref.get
    } yield result).unsafeToFuture().map { result =>
      assert(result)
    }
  }

  it should "be able to wait for messages to be processed (children)" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(Props[IO](WaitSpecSuite.SlowActor(ref)), "waiting")
      _ <- input ! "slow"
      // Print all children so we are sure!
      names <- system.allChildren >>= (children => children.traverse(_.path.name.pure[IO]))
      _ <- system.waitForIdle()
      result <- ref.get
    } yield (names, result)).unsafeToFuture().map { case (names, result) =>
      assert(result)
      assert(names.toSet == Set("user", "waiting", "debug-waiting"))
    }
  }

  it should "be able to wait for messages to be processed recursively" in {
    (for {
      system <- ActorSystem[IO]("Waiting Game", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of(false)
      input <- system.actorOfWithDebug(
        Props[IO](WaitSpecSuite.SlowActorWithForward(ref, 3)),
        "waiting-3"
      )
      _ <- input ! "slow" // assert that at least the first actor received it in his queue.
      names <- system.waitForIdle() >>= (children => children.traverse(c => c.path.name.pure[IO]))
      result <- ref.get
    } yield (names, result)).unsafeToFuture().map { case (names, result) =>
      assert(result)
      assert(
        names.toSet == Set(
          "user",
          "waiting-1",
          "debug-waiting-1",
          "waiting-2",
          "debug-waiting-2",
          "debug-waiting-3",
          "waiting-3"
        )
      )
    }
  }

}
