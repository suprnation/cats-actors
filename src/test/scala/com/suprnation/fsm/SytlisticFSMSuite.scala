package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.instances._
import com.suprnation.typelevel.fsm.syntax.{FSMStateSyntaxOps, when}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

// Define states
sealed trait State
case object Locked extends State
case object Unlocked extends State

// Define messages
sealed trait Input
case object Coin extends Input
case object Push extends Input

object Turnstile {
  val turnstileReplyingActorUsingFsmBuilder: IO[Actor[IO, Input]] =
    FSMBuilder[IO, State, Unit, Input, Any]()
      .when(Locked) { case (Event(Coin, _), sM) =>
        sM.goto(Unlocked).replying(Unlocked)
      }
      .when(Unlocked) { case (Event(Push, _), sM) =>
        sM.goto(Locked)
          .using(())
          .replying(Locked)
      }
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingWhenSyntaxDirectly: IO[Actor[IO, Input]] =
    when[IO, State, Unit, Input, Any](Locked) { case (Event(Coin, _), sM) =>
      sM.goto(Unlocked).replying(Unlocked)
    }
      .when(Unlocked) { case (Event(Push, _), sM) =>
        sM.goto(Locked).using(()).replying(Locked)
      }
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingSemigroupConstruction: IO[Actor[IO, Input]] =
    (
      when[IO, State, Unit, Input, Any](Locked) { case (Event(Coin, _), sM) =>
        sM.goto(Unlocked).replying(Unlocked)
      } |+|
        when[IO, State, Unit, Input, Any](Unlocked) { case (Event(Push, _), sM) =>
          sM.goto(Locked)
            .using(())
            .replying(Locked)
        }
    )
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

}
class FSMStyleSuite extends AsyncFlatSpec with Matchers {
  it should "allow an FMSBuilder style to defined an FSM" in {
    (for {
      actorSystem <- ActorSystem[IO](
        "FSM ReplyingActor",
        (_: Any) => IO.unit
      ).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf(
        Turnstile.turnstileReplyingActorUsingFsmBuilder,
        "turnstile-ReplyingActor-1"
      )

      turnstileReplyingActor <- actorSystem.actorOf[Input](
        AbsorbReplyActor(vendingMachine, buffer),
        "turnstile-absorb-ReplyingActor"
      )
      _ <- turnstileReplyingActor ! Coin
      _ <- turnstileReplyingActor ! Push
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(
        List(
          Unlocked,
          Locked
        )
      )
    }
  }

  it should "allow direct `when` syntax to specify an FSM" in {
    (for {
      actorSystem <- ActorSystem[IO](
        "FSM ReplyingActor",
        (_: Any) => IO.unit
      ).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf(
        Turnstile.turnstileReplyingActorUsingWhenSyntaxDirectly,
        "turnstile-ReplyingActor-1"
      )

      turnstileReplyingActor <- actorSystem.actorOf[Input](
        AbsorbReplyActor(vendingMachine, buffer),
        "turnstile-absorb-ReplyingActor"
      )
      _ <- turnstileReplyingActor ! Coin
      _ <- turnstileReplyingActor ! Push
      _ <- actorSystem.waitForIdle()
      messages <- buffer.get
    } yield messages).unsafeToFuture().map { messages =>
      messages.toList should be(
        List(
          Unlocked,
          Locked
        )
      )
    }
  }
}
