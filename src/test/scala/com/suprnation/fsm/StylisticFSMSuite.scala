package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.instances._
import com.suprnation.typelevel.fsm.syntax.{FSMStateSyntaxOps, when}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import com.suprnation.actor.ReplyingActor

// Define states
sealed trait State
case object Locked extends State
case object Unlocked extends State

// Define messages
sealed trait Input
case object Coin extends Input
case object Push extends Input

object Turnstile {
  val turnstileReplyingActorUsingFsmBuilder: IO[ReplyingActor[IO, Input, List[State]]] =
    FSMBuilder[IO, State, Unit, Input, State]()
      .when(Locked)(sM => { case Event(Coin, _) =>
        sM.goto(Unlocked).replying(Unlocked)
      })
      .when(Unlocked)(sM => { case Event(Push, _) =>
        sM.goto(Locked)
          .using(())
          .replying(Locked)
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingWhenSyntaxDirectly: IO[ReplyingActor[IO, Input, List[State]]] =
    when[IO, State, Unit, Input, State](Locked)(sM => { case Event(Coin, _) =>
      sM.goto(Unlocked).replying(Unlocked)
    })
      .when(Unlocked)(sM => { case Event(Push, _) =>
        sM.goto(Locked).using(()).replying(Locked)
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingSemigroupConstruction: IO[ReplyingActor[IO, Input, List[State]]] =
    (
      when[IO, State, Unit, Input, State](Locked)(sM => { case Event(Coin, _) =>
        sM.goto(Unlocked).replying(Unlocked)
      }) |+|
        when[IO, State, Unit, Input, State](Unlocked)(sM => { case Event(Push, _) =>
          sM.goto(Locked)
            .using(())
            .replying(Locked)
        })
    )
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

}
class FSMStyleSuite extends AsyncFlatSpec with Matchers {
  it should "allow an FMSBuilder style to defined an FSM" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          buffer <- Ref[IO].of(Vector.empty[Any])
          turnstileReplyingActor <- actorSystem.replyingActorOf(
            Turnstile.turnstileReplyingActorUsingFsmBuilder,
            "turnstile-ReplyingActor-1"
          )

          r0 <- turnstileReplyingActor ? Coin
          r1 <- turnstileReplyingActor ? Push
          _ <- actorSystem.waitForIdle()
          messages <- buffer.get
        } yield r0 ++ r1
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(
          List(
            Unlocked,
            Locked
          )
        )
      }
  }

  it should "allow direct `when` syntax to specify an FSM" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          turnstileReplyingActor <- actorSystem.replyingActorOf(
            Turnstile.turnstileReplyingActorUsingWhenSyntaxDirectly,
            "turnstile-ReplyingActor-1"
          )

          r0 <- turnstileReplyingActor ? Coin
          r1 <- turnstileReplyingActor ? Push
          _ <- actorSystem.waitForIdle()
        } yield r0 ++ r1
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(
          List(
            Unlocked,
            Locked
          )
        )
      }
  }
}
