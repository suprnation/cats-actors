package com.suprnation.actor.become

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.become.BecomeUnBecome.{CalculatorWithBecome, CalculatorWithBecomeUnstacked, CalculatorWithImplicitState}
import com.suprnation.actor.props.Props
import com.suprnation.actor.{Actor, ActorSystem}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object BecomeUnBecome {

  def createCalculatorWithRef: IO[Actor[IO]] =
    for {
      // State defined here...
      ref <- Ref.of[IO, Int](0)
    } yield new Actor[IO] {
      override def receive: Receive[IO] = {
        case "incr"  => ref.update(_ + 1)
        case "decr"  => ref.update(_ - 1)
        case "value" => ref.get
      }
    }

  class CalculatorWithImplicitState extends Actor[IO] {
    var value = 0

    override def receive: Receive[IO] = {
      case "incr"  => IO.delay(value += 1)
      case "decr"  => IO.delay(value -= 1)
      case "value" => IO.pure(value)
    }
  }

  class CalculatorWithBecome extends Actor[IO] {
    // State defined implicitly
    def withCurrentValue(number: Int): Receive[IO] = {
      case "incr"  => context.become(withCurrentValue(number + 1), discardOld = false)
      case "decr"  => context.unbecome
      case "value" => IO.pure(number)
    }

    override def receive: Receive[IO] = withCurrentValue(0)
  }

  class CalculatorWithBecomeUnstacked extends Actor[IO] {
    // State defined implicitly
    def withCurrentValue(number: Int): Receive[IO] = {
      case "incr"  => context.become(withCurrentValue(number + 1))
      case "decr"  => context.become(withCurrentValue(number - 1))
      case "value" => IO.pure(number)

    }

    override def receive: Receive[IO] = withCurrentValue(0)
  }
}

/** This test suite is geared towards creating a realistic scenario which creates increasingly more complex systems.
  */
class BecomeUnBecomeSpec extends AsyncFlatSpec with Matchers {

  it should "(Implicit State) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.actorOf(Props[IO](new CalculatorWithImplicitState()))
      result1 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result2 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result3 <- (calculator ! "decr") >> (calculator ? [Integer] "value")
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }

  it should "(Become) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.actorOf(Props[IO](new CalculatorWithBecome()))
      result1 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result2 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result3 <- (calculator ! "decr") >> (calculator ? [Integer] "value")
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }

  it should "(Become - Unstacked) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      calculator <- system.actorOf(Props[IO](new CalculatorWithBecomeUnstacked()))
      result1 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result2 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result3 <- (calculator ! "decr") >> (calculator ? [Integer] "value")
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }

  it should "(Ref) increment and decrement correctly" in {
    (for {
      system <- ActorSystem[IO]("HelloSystem", (_: Any) => IO.unit).allocated.map(_._1)
      actor <- BecomeUnBecome.createCalculatorWithRef
      calculator <- system.actorOf(Props[IO](actor))
      result1 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result2 <- (calculator ! "incr") >> (calculator ? [Integer] "value")
      result3 <- (calculator ! "decr") >> (calculator ? [Integer] "value")
    } yield (result1, result2, result3)).unsafeToFuture().map { case (result1, result2, result3) =>
      result1 should be(1)
      result2 should be(2)
      result3 should be(1)
    }
  }
}
