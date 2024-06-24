package com.suprnation.actor.become

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.become.BecomeUnBecome._
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object BecomeUnBecome {

  trait Input
  case object Increment extends Input
  case object Decrement extends Input
  case object Value extends Input

  def createCalculatorWithRef: IO[ReplyingActor[IO, Input, Int]] =
    for {
      // State defined here...
      ref <- Ref.of[IO, Int](0)
    } yield new ReplyingActor[IO, Input, Int] {
      override def receive: ReplyingReceive[IO, Input, Int] = {
        case Increment => ref.updateAndGet(_ + 1)
        case Decrement => ref.updateAndGet(_ - 1)
        case Value     => ref.get
      }
    }

  def calculatorWithImplicitState: ReplyingActor[IO, Input, Int] =
    new ReplyingActor[IO, Input, Int] {
      var value: Int = 0
      override def receive: ReplyingReceive[IO, Input, Int] = {
        case Increment => IO.delay(value += 1).as(value)
        case Decrement => IO.delay(value -= 1).as(value)
        case Value     => IO.pure(value).as(value)
      }
    }

  def calculatorWithBecome: ReplyingActor[IO, Input, Option[Int]] =
    new ReplyingActor[IO, Input, Option[Int]] {
      def withCurrentValue(number: Int): ReplyingReceive[IO, Input, Option[Int]] = {
        case Increment => context.become(withCurrentValue(number + 1), discardOld = false).as(None)
        case Decrement => context.unbecome.void.as(None)
        case Value     => IO.pure(number.some)
      }

      override def receive: ReplyingReceive[IO, Input, Option[Int]] = withCurrentValue(0)
    }

  def calculatorWithBecomeUnstacked: ReplyingActor[IO, Input, Option[Int]] =
    new ReplyingActor[IO, Input, Option[Int]] {
      // State defined implicitly
      def withCurrentValue(number: Int): ReplyingReceive[IO, Input, Option[Int]] = {
        case Increment => context.become(withCurrentValue(number + 1)).as(None)
        case Decrement => context.become(withCurrentValue(number - 1)).as(None)
        case Value     => IO.pure(number.some)
      }

      override def receive: ReplyingReceive[IO, Input, Option[Int]] = withCurrentValue(0)
    }
}

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class BecomeUnBecomeSpec extends AsyncFlatSpec with Matchers {

  it should "(Implicit State) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.replyingActorOf(calculatorWithImplicitState)
      result1 <- (calculator ! Increment) >> (calculator ? Value)
      result2 <- (calculator ! Increment) >> (calculator ? Value)
      result3 <- (calculator ! Decrement) >> (calculator ? Value)
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }

  it should "(Become) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.replyingActorOf(calculatorWithBecome)
      result1 <- (calculator ! Increment) >> (calculator ? Value)
      result2 <- (calculator ! Increment) >> (calculator ? Value)
      result3 <- (calculator ! Decrement) >> (calculator ? Value)
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(Option(1))
      result2 should be(Option(2))
      result3 should be(Option(1))
    }
  }

  it should "(Become - Unstacked) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.replyingActorOf(calculatorWithBecomeUnstacked)
      result1 <- (calculator ! Increment) >> (calculator ? Value)
      result2 <- (calculator ! Increment) >> (calculator ? Value)
      result3 <- (calculator ! Decrement) >> (calculator ? Value)
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(Option(1))
      result2 should be(Option(2))
      result3 should be(Option(1))
    }
  }

  it should "(Ref) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      actor <- BecomeUnBecome.createCalculatorWithRef
      calculator <- system.replyingActorOf(actor)
      result1 <- (calculator ! Increment) >> (calculator ? Value)
      result2 <- (calculator ! Increment) >> (calculator ? Value)
      result3 <- (calculator ! Decrement) >> (calculator ? Value)
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }
}
