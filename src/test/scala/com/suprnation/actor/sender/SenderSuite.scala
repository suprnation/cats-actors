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

package com.suprnation.actor.sender

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor.ActorSystem
import com.suprnation.actor.sender.Sender.BaseActor.{Ask, BaseActorMessages, Forward, Tell}
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object Sender {
  case class ForwardActor(
      forwardTo: ActorRef[IO, BaseActorMessages],
      ref: Ref[IO, Option[NoSendActorRef[IO]]]
  ) extends Actor[IO, BaseActorMessages] {
    override def receive: Receive[IO, BaseActorMessages] = {
      case Tell(msg) =>
        ref.set(
          sender
        ) >> (forwardTo ! Tell(msg))
      case Ask(msg) =>
        ref.set(
          sender
        ) >> (forwardTo ? Ask(msg))
      case Forward(msg, true) =>
        ref.set(
          sender
        ) >> (forwardTo ! Forward(msg, swapCurrentReceivingActorAsActorRef = false))
      case f @ Forward(_, false) =>
        ref.set(
          sender
        ) >> forwardTo.>>!(f)
    }
  }

  case class BaseActor(ref: Ref[IO, Option[NoSendActorRef[IO]]])
      extends Actor[IO, BaseActorMessages] {
    override def receive: Receive[IO, BaseActorMessages] = {
      // Set the sender and set the IO to be the msg.
      case Tell(msg)       => ref.set(sender).as(msg)
      case Ask(msg)        => ref.set(sender).as(msg)
      case Forward(msg, _) => ref.set(sender).as(msg)
      case msg => IO.raiseError(new IllegalStateException(s"Received unknown message $msg"))
    }
  }

  object ExampleCatsActor {
    trait SenderSuiteMessages
    case class Shutdown() extends SenderSuiteMessages

    case class Request(echoMessage: String) extends SenderSuiteMessages

    case class Dangerous(echoMessage: String, crash: Boolean) extends SenderSuiteMessages

    case class JobRequest(
        echoMessage: String,
        sender: ActorRef[IO, SenderSuiteMessages],
        crash: Boolean
    ) extends SenderSuiteMessages

    case class JobReply(
        echoMessage: String,
        originalSender: ActorRef[IO, SenderSuiteMessages]
    ) extends SenderSuiteMessages
  }

  object BaseActor {
    trait BaseActorMessages
    case class Tell(msg: String) extends BaseActorMessages
    case class Ask(msg: String) extends BaseActorMessages
    case class Forward(msg: String, swapCurrentReceivingActorAsActorRef: Boolean)
        extends BaseActorMessages
    case class Message(msg: String) extends BaseActorMessages
  }
}

class SenderSuite extends AsyncFlatSpec with Matchers {

  it should "include itself as a sender on messages when using tell.  " in {
    (for {
      system <- ActorSystem[IO]("sender-system", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      baseActor <- system.actorOf[BaseActorMessages](
        Sender.BaseActor(ref),
        "base-actor"
      )
      appRef <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      forwardActor <- system.actorOf[BaseActorMessages](
        Sender.ForwardActor(baseActor, appRef),
        "forward-actor"
      )
      // Send a message to the app actor
      _ <- forwardActor ! Tell("hello")
      _ <- system.waitForIdle()

      // The app actor will forward to the forward actor, let's capture the sender from that actor.
      senderActor <- ref.get
    } yield (forwardActor, senderActor)).unsafeToFuture().map { case (appActor, senderActor) =>
      assert(senderActor.isDefined)
      assert(appActor == senderActor.get)
    }
  }

  it should "include itself as a sender on messages when using ask.  " in {
    (for {
      system <- ActorSystem[IO]("sender-system-2", (_: Any) => IO.unit).allocated.map(_._1)
      sinkSenderRef <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      baseActor <- system.actorOf[BaseActorMessages](
        Sender.BaseActor(sinkSenderRef),
        "sink"
      )
      forwardSenderRef <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      forwardActor <- system.actorOf[BaseActorMessages](
        Sender.ForwardActor(baseActor, forwardSenderRef),
        "forward-actor"
      )
      // Send a message to the app actor
      _ <- forwardActor ! Ask("hello")
      _ <- system.waitForIdle()

      // The app actor will forward to the forward actor, let's capture the sender from that actor.
      senderActor <- sinkSenderRef.get
    } yield (forwardActor, senderActor)).unsafeToFuture().map { case (appActor, senderActor) =>
      assert(senderActor.isDefined)
      assert(appActor == senderActor.get)
    }
  }

  it should "include itself as a sender on messages when using tell. (double forward)  " in {
    (for {
      system <- ActorSystem[IO]("sender-system-3", (_: Any) => IO.unit).allocated.map(_._1)
      ref2 <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      sinkActor <- system.actorOf[BaseActorMessages](
        Sender.BaseActor(ref2),
        "base-actor"
      )

      ref1 <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      forwardActor2 <- system.actorOf[BaseActorMessages](
        Sender.ForwardActor(sinkActor, ref1),
        "forward-actor-2"
      )

      appRef <- Ref[IO].of[Option[NoSendActorRef[IO]]](None)
      forwardActor1 <- system.actorOf[BaseActorMessages](
        Sender.ForwardActor(forwardActor2, appRef),
        "forward-actor-1"
      )

      // Send a message to the app actor
      _ <- forwardActor1 ! Forward("hello", swapCurrentReceivingActorAsActorRef = true)
      _ <- system.waitForIdle()

      // The app actor will forward to the forward actor, let's capture the sender from that actor.
      appReceiveRef <- appRef.get
      forward1ReceiveRef <- ref1.get
      forward2ReceiveRef <- ref2.get

    } yield (appReceiveRef, forward1ReceiveRef, forward2ReceiveRef, forwardActor1))
      .unsafeToFuture()
      .map { case (app, forward1, forward2, sender) =>
        assert(app.isEmpty)
        assert(forward1.isDefined)
        assert(forward2.isDefined)
        assert(forward1.contains(sender))
        assert(forward2.contains(sender))
      }
  }
}
