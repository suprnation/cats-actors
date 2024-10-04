/*
 * Copyright 2024 SuprNation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.instances._
import com.suprnation.typelevel.fsm.syntax._
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
    FSMBuilder[IO, State, Unit, Input, List[State]]()
      .when(Locked)(sM => { case Event(Coin, _) =>
        sM.goto(Unlocked).returning(List(Unlocked))
      })
      .when(Unlocked)(sM => { case Event(Push, _) =>
        sM.goto(Locked)
          .using(())
          .returning(List(Locked))
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingWhenSyntaxDirectly: IO[ReplyingActor[IO, Input, List[State]]] =
    when[IO, State, Unit, Input, List[State]](Locked)(sM => { case Event(Coin, _) =>
      sM.goto(Unlocked).returning(List(Unlocked))
    })
      .when(Unlocked)(sM => { case Event(Push, _) =>
        sM.goto(Locked).using(()).returning(List(Locked))
      })
      .withConfig(FSMConfig.withConsoleInformation)
      .startWith(Locked, ())
      .initialize

  val turnstileReplyingActorUsingSemigroupConstruction: IO[ReplyingActor[IO, Input, List[State]]] =
    (
      when[IO, State, Unit, Input, List[State]](Locked)(sM => { case Event(Coin, _) =>
        sM.goto(Unlocked).returning(List(Unlocked))
      }) |+|
        when[IO, State, Unit, Input, List[State]](Unlocked)(sM => { case Event(Push, _) =>
          sM.goto(Locked)
            .using(())
            .returning(List(Locked))
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
          turnstileReplyingActor <- actorSystem.replyingActorOf(
            Turnstile.turnstileReplyingActorUsingFsmBuilder,
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
