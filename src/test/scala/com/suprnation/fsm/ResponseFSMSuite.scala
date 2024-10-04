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
import com.suprnation.actor.Actor.{Actor, ReplyingReceive}
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.{ReplyingActor, ReplyingActorRef}
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSMBuilder, FSMConfig}
import com.suprnation.actor.fsm.State.replies._
import com.suprnation.typelevel.actors.syntax._
import com.suprnation.typelevel.fsm.instances._
import com.suprnation.typelevel.fsm.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.HashMap

object ChattyTurnstile {

  val turnstileReplyingFsm: IO[ReplyingActor[IO, Input, List[State]]] =
    FSMBuilder[IO, State, Unit, Input, List[State]]()
      .when(Locked)(sM => {
        case Event(Coin, _) =>
          sM.goto(Unlocked).replying(List(Unlocked)).replying(List(Unlocked))
        case Event(Push, _) =>
          sM.stayAndReply(List(Locked))
      })
      .when(Unlocked)(sM => {
        case Event(Push, _) =>
          sM.goto(Locked).replying(List(Locked)).replying(List(Locked))
        case Event(Coin, _) =>
          sM.stayAndReply(List(Unlocked))
      })
      .startWith(Locked, ())
      .initialize

  val turnstileReturningFsm: IO[ReplyingActor[IO, Input, List[State]]] =
    FSMBuilder[IO, State, Unit, Input, List[State]]()
      .when(Locked)(sM => {
        case Event(Coin, _) =>
          sM.goto(Unlocked).returning(List(Unlocked)).returning(List(Unlocked))
        case Event(Push, _) =>
          sM.stayAndReturn(List(Locked))
      })
      .when(Unlocked)(sM => {
        case Event(Push, _) =>
          sM.goto(Locked).returning(List(Locked)).returning(List(Locked))
        case Event(Coin, _) =>
          sM.stayAndReturn(List(Unlocked))
      })
      .startWith(Locked, ())
      .initialize

  val turnstileReturningAndReplyingFsm: IO[ReplyingActor[IO, Input, List[State]]] =
    FSMBuilder[IO, State, Unit, Input, List[State]]()
      .when(Locked)(sM => {
        case Event(Coin, _) =>
          sM.goto(Unlocked)
            .replying(List(Unlocked), BothMessageAndResponse)
            .replying(List(Unlocked), BothMessageAndResponse)
        case Event(Push, _) =>
          sM.stayAndReply(List(Locked), BothMessageAndResponse)
      })
      .when(Unlocked)(sM => {
        case Event(Push, _) =>
          sM.goto(Locked)
            .replying(List(Locked), BothMessageAndResponse)
            .replying(List(Locked), BothMessageAndResponse)
        case Event(Coin, _) =>
          sM.stayAndReply(List(Unlocked), BothMessageAndResponse)
      })
      .startWith(Locked, ())
      .initialize

  case class ProxyActor(fsmActor: ReplyingActorRef[IO, Input, List[State]])
      extends ReplyingActor[IO, Any, List[State]] {
    override def receive: ReplyingReceive[IO, Any, List[State]] = {
      case s: Input => fsmActor ? s
      case _        => List().pure[IO]
    }
  }
}

class ResponseFSMSuite extends AsyncFlatSpec with Matchers {
  it should "respond properly by sending messages" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          fsmActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.turnstileReplyingFsm,
            "turnstileReplyingFsm"
          )
          proxyActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.ProxyActor(fsmActor).track("turnstileReplyingFsm")(cache),
            "proxy"
          )

          r0 <- proxyActor ? Coin
          r1 <- proxyActor ? Push
          _ <- actorSystem.waitForIdle()
          messages <- proxyActor.messageBuffer
        } yield (messages, r0 ++ r1)
      }
      .unsafeToFuture()
      .map { case ((_, messages), returns) =>
        messages should be(
          List(
            Coin,
            List(Unlocked, Unlocked),
            Push,
            List(Locked, Locked)
          )
        )
        returns shouldBe empty
      }
  }

  it should "respond properly by returning responses" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          fsmActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.turnstileReturningFsm,
            "turnstileReturningFsm"
          )
          proxyActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.ProxyActor(fsmActor).track("turnstileReturningFsm")(cache),
            "proxy"
          )

          r0 <- proxyActor ? Coin
          r1 <- proxyActor ? Push
          _ <- actorSystem.waitForIdle()
          messages <- proxyActor.messageBuffer
        } yield (messages, r0 ++ r1)
      }
      .unsafeToFuture()
      .map { case ((_, messages), returns) =>
        messages should be(
          List(
            Coin,
            List(),
            Push,
            List()
          )
        )
        returns should be(
          List(
            Unlocked,
            Unlocked,
            Locked,
            Locked
          )
        )
      }
  }

  it should "respond properly by sending messages and returning responses" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
            HashMap.empty[String, TrackingActor.ActorRefs[IO]]
          )
          fsmActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.turnstileReturningAndReplyingFsm,
            "turnstileReturningAndReplyingFsm"
          )
          proxyActor <- actorSystem.replyingActorOf(
            ChattyTurnstile.ProxyActor(fsmActor).track("turnstileReturningAndReplyingFsm")(cache),
            "proxy"
          )

          r0 <- proxyActor ? Coin
          r1 <- proxyActor ? Push
          _ <- actorSystem.waitForIdle()
          messages <- proxyActor.messageBuffer
        } yield (messages, r0 ++ r1)
      }
      .unsafeToFuture()
      .map { case ((_, messages), returns) =>
        messages should be(
          List(
            Coin,
            List(Unlocked, Unlocked),
            Push,
            List(Locked, Locked)
          )
        )
        returns should be(
          List(
            Unlocked,
            Unlocked,
            Locked,
            Locked
          )
        )
      }
  }

}
