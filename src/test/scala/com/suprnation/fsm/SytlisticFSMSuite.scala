package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits.catsSyntaxSemigroup
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.FSMBuilder
import com.suprnation.actor.props.{Props, PropsF}
import com.suprnation.actor.{Actor, ActorSystem}
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
  val turnstileActorUsingFsmBuilder: IO[Actor[IO]] =
    FSMBuilder[IO, State, Unit]()
      .when(Locked) { case (Event(Coin, _), sM) =>
        sM.goto(Unlocked).replying(Unlocked)
      }
      .when(Unlocked) { case (Event(Push, _), sM) =>
        sM.goto(Locked)
          .using(())
          .replying(Locked)
      }
//      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileActorUsingWhenSyntaxDirectly: IO[Actor[IO]] =
    when[IO, State, Unit](Locked) { case (Event(Coin, _), sM) =>
      sM.goto(Unlocked).replying(Unlocked)
    }
      .when(Unlocked) { case (Event(Push, _), sM) =>
        sM.goto(Locked).using(()).replying(Locked)
      }
//      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileActorUsingSemigroupConstruction: IO[Actor[IO]] =
    (
      when[IO, State, Unit](Locked) { case (Event(Coin, _), sM) =>
        sM.goto(Unlocked).replying(Unlocked)
      } |+|
        when[IO, State, Unit](Unlocked) { case (Event(Push, _), sM) =>
          sM.goto(Locked)
            .using(())
            .replying(Locked)
        }
    )
//      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

}
class FSMStyleSuite extends AsyncFlatSpec with Matchers {
  it should "allow an FMSBuilder style to defined an FSM" in {
    (for {
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf(
        PropsF[IO](
          Turnstile.turnstileActorUsingFsmBuilder
        ),
        "turnstile-actor-1"
      )

      turnstileActor <- actorSystem.actorOf(
        Props[IO](AbsorbReplyActor(vendingMachine, buffer)),
        "turnstile-absorb-actor"
      )
      _ <- turnstileActor ! Coin
      _ <- turnstileActor ! Push
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
      actorSystem <- ActorSystem[IO]("FSM Actor", (_: Any) => IO.unit).allocated.map(_._1)
      buffer <- Ref[IO].of(Vector.empty[Any])
      vendingMachine <- actorSystem.actorOf(
        PropsF[IO](
          Turnstile.turnstileActorUsingWhenSyntaxDirectly
        ),
        "turnstile-actor-1"
      )

      turnstileActor <- actorSystem.actorOf(
        Props[IO](AbsorbReplyActor(vendingMachine, buffer)),
        "turnstile-absorb-actor"
      )
      _ <- turnstileActor ! Coin
      _ <- turnstileActor ! Push
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
