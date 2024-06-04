package com.suprnation.actor.lifecycle

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor._
import com.suprnation.actor.props.Props
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class ActorLifecycleSuite extends AsyncFlatSpec with Matchers {
  it should "call the preStart hook before the actor start" in {
    (for {
      actorSystem <- ActorSystem[IO]("Actor Test", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref.of[IO, Int](0)
      actor <- actorSystem.actorOf(
        Props[IO](
          new Actor[IO] {
            override def receive: Actor.Receive[IO] = { case _ => ref.get }
            override def preStart: IO[Unit] = ref.set(1)
          }
        )
      )
      result1 <-
        actor ? [Int] "getRef" // send a message to the actor to confirm that they received it.
    } yield result1).unsafeToFuture().map(result1 => result1 should be(1))
  }

  it should "call the postHook when the actor dies" in {
    (for {
      actorSystem <- ActorSystem[IO]("Actor Test", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref.of[IO, Int](0)
      actor <- actorSystem.actorOf(
        Props[IO](
          new Actor[IO] {

            override def receive: Receive[IO] = { case "stop" =>
              context.stop(self)
            }
            override def postStop: IO[Unit] = ref.set(1) >> actorSystem.terminate(None).void
          }
        )
      )
      _ <- actor ! "stop"
      _ <- actorSystem.waitForTermination
      result1 <- ref.get
    } yield result1).unsafeToFuture().map(result1 => result1 should be(1))
  }

  it should "allow an actor to be recreated once it is killed" in {

    def createActor: Actor[IO] = new Actor[IO] {
      override def receive: Receive[IO] = {
        case "stop"  => context.stop(self).as(true)
        case "hello" => IO("hello")
      }
    }
    (for {
      actorSystem <- ActorSystem[IO]("Actor Test", (_: Any) => IO.unit).allocated.map(_._1)

      actor <- actorSystem.actorOf(Props[IO](createActor), "testing")
      _ <- actor ? [String] "hello"
      _ <- actor ? [Boolean] "stop"
      _ <- actorSystem.waitForIdle()

      actor <- actorSystem.actorOf(Props[IO](createActor), "testing")
      _ <- actor ? [String] "hello"
      result1 <- IO.pure(1)
    } yield result1).unsafeToFuture().map(result1 => result1 should be(1))
  }

}
