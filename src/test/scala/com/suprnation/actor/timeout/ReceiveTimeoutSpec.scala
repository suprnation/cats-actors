package com.suprnation.actor.timeout

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor._
import com.suprnation.actor.props.Props
import com.suprnation.actor.timeout.Suspension.ConstantFlowActor
import com.suprnation.actor.timeout.Suspension.ConstantFlowActor.{CancelTimeout, RescheduleTimeout}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

object Suspension {

  case class ConstantFlowActor(
      timeout: FiniteDuration,
      ref: Ref[IO, Int],
      buffer: Ref[IO, List[Int]]
  ) extends Actor[IO] {
    override def preStart: IO[Unit] = context.setReceiveTimeout(timeout)

    override def receive: Receive[IO] = {
      case ReceiveTimeout => ref.updateAndGet(_ + 1)
      case msg: Int       => buffer.updateAndGet(msg :: _)

      case RescheduleTimeout(f) => context.setReceiveTimeout(f)

      case CancelTimeout => context.cancelReceiveTimeout

      case "get" =>
        for {
          counter <- ref.get
          list <- buffer.get
        } yield (counter, list)
    }
  }

  object ConstantFlowActor {

    case class RescheduleTimeout(f: FiniteDuration)
    case object CancelTimeout
  }
}

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class ReceiveTimeoutSpec extends AsyncFlatSpec with Matchers {

  it should "be able to set a receive timeout" in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem").allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.actorOf(
        Props[IO](ConstantFlowActor(1 second, counter, buffer)),
        name = "hello-actor"
      )
      _ <- IO.sleep(
        0.8 second
      ) // timeout processing is tight with ping event that is emitted every 1s, so let the event some space at the beginning

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result1 <- helloActor ? [(Int, List[Int])] "get"
      _ <- buffer.update(_ => List.empty)

      _ <- helloActor ! 3
      _ <- helloActor ! 4
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result2 <- helloActor ? [(Int, List[Int])] "get"
      _ <- buffer.update(_ => List.empty)

      _ <- helloActor ?! 5
      // Stop processing for 1 second
      _ <- IO.sleep(1.8 second)
      result3 <- helloActor ? [(Int, List[Int])] "get"
      _ <- buffer.update(_ => List.empty)
    } yield (result1, result2, result3)).unsafeToFuture().map {
      case ((r1, b1), (r2, b2), (r3, b3)) =>
        r1 should be(1)
        b1 should be(List(2, 1))

        r2 should be(2)
        b2 should be(List(4, 3))

        r3 should be(3)
        b3 should be(List(5))
    }
  }

  it should "be able to update a receive timeout in realtime.  " in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.actorOf(
        Props[IO](ConstantFlowActor(1 millis, counter, buffer)),
        name = "hello-actor"
      )

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 millis
      _ <- IO.sleep(9 millis)
      _ <- helloActor ! RescheduleTimeout(30 millis)
      _ <- IO.sleep(28 millis)
      result1 <- helloActor ? [(Int, List[Int])] "get"
      _ <- buffer.update(_ => List.empty)
    } yield result1).unsafeToFuture().map { case ((r1, b1)) =>
      r1 should be(0)
      b1 should be(List(2, 1))
    }
  }

  it should "be able to cancel a receive timeout in realtime.  " in {
    (for {
      counter <- Ref.of[IO, Int](0)
      buffer <- Ref.of[IO, List[Int]](List.empty)
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      //   default Actor constructor
      helloActor <- system.actorOf(
        Props[IO](ConstantFlowActor(1 millis, counter, buffer)),
        name = "hello-actor"
      )

      _ <- helloActor ! 1
      _ <- helloActor ! 2
      // Stop processing for 1 millis
      _ <- IO.sleep(9 millis)
      _ <- helloActor ! CancelTimeout
      _ <- IO.sleep(28 millis)
      result1 <- helloActor ? [(Int, List[Int])] "get"
      _ <- buffer.update(_ => List.empty)
    } yield result1).unsafeToFuture().map { case ((r1, b1)) =>
      r1 should be(0)
      b1 should be(List(2, 1))
    }
  }
}
