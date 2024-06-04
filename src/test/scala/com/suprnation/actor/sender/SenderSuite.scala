package com.suprnation.actor.sender

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.props.Props
import com.suprnation.actor.sender.Sender.BaseActor.{Ask, Forward, Tell}
import com.suprnation.actor.{Actor, ActorRef, ActorSystem}
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

object Sender {
  case class ForwardActor(forwardTo: ActorRef[IO], ref: Ref[IO, Option[ActorRef[IO]]])
      extends Actor[IO] {
    override def receive: Receive[IO] = {
      case Tell(msg) =>
        ref.set(
          sender
        ) >> (forwardTo ! Tell(msg))
      case Ask(msg) =>
        ref.set(
          sender
        ) >> (forwardTo ? [String] Ask(msg))
      case Forward(msg, true) =>
        ref.set(
          sender
        ) >> (forwardTo ! Forward(msg, swapCurrentReceivingActorAsActorRef = false))
      case f @ Forward(_, false) =>
        ref.set(
          sender
        ) >> forwardTo.forward(f)
    }
  }

  case class BaseActor(ref: Ref[IO, Option[ActorRef[IO]]]) extends Actor[IO] {
    override def receive: Receive[IO] = {
      // Set the sender and set the IO to be the msg.
      case Tell(msg)       => ref.set(sender).as(msg)
      case Ask(msg)        => ref.set(sender).as(msg)
      case Forward(msg, _) => ref.set(sender).as(msg)
      case msg             => IO.raiseError(new IllegalStateException(s"Received unknown message $msg"))
    }
  }

  object ExampleCatsActor {
    case class Shutdown()

    case class Request(echoMessage: String)

    case class Dangerous(echoMessage: String, crash: Boolean)

    case class JobRequest(echoMessage: String, sender: ActorRef[IO], crash: Boolean)

    case class JobReply(echoMessage: String, originalSender: ActorRef[IO])
  }

  object BaseActor {
    case class Tell(msg: String)
    case class Ask(msg: String)

    case class Forward(msg: String, swapCurrentReceivingActorAsActorRef: Boolean)
  }
}

class SenderSuite extends AsyncFlatSpec with Matchers {

  it should "include itself as a sender on messages when using tell.  " in {
    (for {
      system <- ActorSystem[IO]("sender-system", (_: Any) => IO.unit).allocated.map(_._1)
      ref <- Ref[IO].of[Option[ActorRef[IO]]](None)
      baseActor <- system.actorOf(Props[IO](Sender.BaseActor(ref)), "base-actor")
      appRef <- Ref[IO].of[Option[ActorRef[IO]]](None)
      forwardActor <- system.actorOf(
        Props[IO](Sender.ForwardActor(baseActor, appRef)),
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

  it should "have a sender which is not defined when using ask.  " in {
    (for {
      system <- ActorSystem[IO]("sender-system-2", (_: Any) => IO.unit).allocated.map(_._1)
      sinkSenderRef <- Ref[IO].of[Option[ActorRef[IO]]](None)
      baseActor <- system.actorOf(Props[IO](Sender.BaseActor(sinkSenderRef)), "sink")
      forwardSenderRef <- Ref[IO].of[Option[ActorRef[IO]]](None)
      forwardActor <- system.actorOf(
        Props[IO](Sender.ForwardActor(baseActor, forwardSenderRef)),
        "forward-actor"
      )
      // Send a message to the app actor
      _ <- forwardActor ! Ask("hello")
      _ <- system.waitForIdle()

      // The app actor will forward to the forward actor, let's capture the sender from that actor.
      senderActor <- sinkSenderRef.get
    } yield (forwardActor, senderActor)).unsafeToFuture().map { case (appActor, senderActor) =>
      assert(senderActor.isEmpty)
    }
  }

  it should "include itself as a sender on messages when using tell. (double forward)  " in {
    (for {
      system <- ActorSystem[IO]("sender-system-3", (_: Any) => IO.unit).allocated.map(_._1)
      ref2 <- Ref[IO].of[Option[ActorRef[IO]]](None)
      sinkActor <- system.actorOf(Props[IO](Sender.BaseActor(ref2)), "base-actor")

      ref1 <- Ref[IO].of[Option[ActorRef[IO]]](None)
      forwardActor2 <- system.actorOf(
        Props[IO](Sender.ForwardActor(sinkActor, ref1)),
        "forward-actor-2"
      )

      appRef <- Ref[IO].of[Option[ActorRef[IO]]](None)
      forwardActor1 <- system.actorOf(
        Props[IO](Sender.ForwardActor(forwardActor2, appRef)),
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
