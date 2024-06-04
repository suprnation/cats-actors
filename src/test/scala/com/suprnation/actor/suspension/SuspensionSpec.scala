package com.suprnation.actor.suspension

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.props.Props
import com.suprnation.actor.{Actor, ActorRef, ActorSystem, InternalActorRef}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object Suspension {

  def commonReceive[F[+_]](self: ActorRef[F]): Receive[IO] = {
    // Cheat a bit to make sure we are able to suspend
    case "suspend" => IO.println("Suspending") >> self.asInstanceOf[InternalActorRef[IO]].suspend()
    // Cheat a bit to make sure we are able to resume
    case "resume" => IO.println("Resuming") >> self.asInstanceOf[InternalActorRef[IO]].resume(None)
    case _      => IO.unit
  }

  case class SuspensionExampleWithPrestart() extends Actor[IO] {
    override def preStart: IO[Unit] = {
      (IO.sleep(30 millis) >>  IO.println("Resuming!! VIL") >>  self.asInstanceOf[InternalActorRef[IO]].resume(None)).start.void
    }

    override def receive: Receive[IO] = commonReceive(self)
  }

  case class SuspensionExample() extends Actor[IO] {
    override def receive: Receive[IO] = commonReceive(self)
  }
}

class SuspensionSpec extends AsyncFlatSpec with Matchers {

  it should "be able to suspend mailbox and resume it (pre-start)" in {
    (for {
      system <- ActorSystem[IO]("Suspension System").allocated.map(_._1)
      input <- system.actorOf(Props[IO](Suspension.SuspensionExampleWithPrestart()), "suspension")
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
      input <- system.actorOf(Props[IO](Suspension.SuspensionExample()), "suspension")
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
