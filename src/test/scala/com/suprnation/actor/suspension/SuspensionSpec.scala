package com.suprnation.actor.suspension

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.suspension.Suspension.{suspensionExample, suspensionExampleWithPreStart}
import com.suprnation.actor.{ActorSystem, InternalActorRef, ReplyingActorRef}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object Suspension {

  def commonReceive[F[+_]](self: ReplyingActorRef[F, String, Any]): Receive[IO, String] = {
    // Cheat a bit to make sure we are able to suspend
    case "suspend" =>
      IO.println("Suspending") >> self.asInstanceOf[InternalActorRef[IO, String, Any]].suspend()
    // Cheat a bit to make sure we are able to resume
    case "resume" =>
      IO.println("Resuming") >> self.asInstanceOf[InternalActorRef[IO, String, Any]].resume(None)
    case _ => IO.unit
  }

  def suspensionExampleWithPreStart: Actor[IO, String] = new Actor[IO, String] {
    override def preStart: IO[Unit] =
      (IO.sleep(30 millis) >> IO.println("Resuming!! VIL") >> self
        .asInstanceOf[InternalActorRef[IO, String, Any]]
        .resume(None)).start.void

    override def receive: Receive[IO, String] = commonReceive(self)
  }

  def suspensionExample: Actor[IO, String] = new Actor[IO, String] {
    override def receive: Receive[IO, String] = commonReceive(context.self)
  }
}

class SuspensionSpec extends AsyncFlatSpec with Matchers {

  it should "be able to suspend mailbox and resume it (pre-start)" in {
    (for {
      system <- ActorSystem[IO]("Suspension System").allocated.map(_._1)
      input <- system.actorOf[String](suspensionExampleWithPreStart, "suspension")
      _ <- input ! "suspend"
      result <- IO.race(IO.sleep(50 millis), input ?! "Post Suspend")
    } yield result).unsafeToFuture().map {
      case Right(_) => succeed
      case Left(_) => fail("The race has completed before the post suspend has reached the mailbox")
    }
  }

  it should "be able to suspend mailbox" in {
    (for {
      system <- ActorSystem[IO]("Suspension System").allocated.map(_._1)
      input <- system.actorOf[String](suspensionExample, "suspension")
      _ <- ((input ! s"Pre-Suspend ${System.currentTimeMillis()}") >> IO.sleep(
        1 millis
      )).foreverM.start
      _ <- input ! "suspend"
      result <- IO.race(IO.sleep(4 millis), input ?! "Post Suspend")
    } yield result).unsafeToFuture().map {
      case Right(_) =>
        fail("The actor is suspended, we should not be able to resume internally from the actor. ")
      case Left(_) => succeed
    }
  }

}
