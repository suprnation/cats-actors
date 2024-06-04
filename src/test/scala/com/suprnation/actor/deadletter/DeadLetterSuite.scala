package com.suprnation.actor.deadletter

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor._
import com.suprnation.actor.deadletter.DeadLetterSuite.DeadLetterActor
import com.suprnation.actor.debug.TrackingActor.ActorRefs
import com.suprnation.actor.event.Debug
import com.suprnation.actor.props.PropsF
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.HashMap

object DeadLetterSuite {
  case class DeadLetterActor() extends Actor[IO] {
    override def receive: Receive[IO] = {
      case "kill" => context.stop(self).as("kill")
      case msg    => msg.pure[IO]
    }

  }
}

class DeadLetterSuite extends AsyncFlatSpec with Matchers {
  it should "swap mailbox when the actor is killed and forward any messages to the dead letter mailbox. (waiting)  " in {
    val numberOfMessages = 100
    val numberOfDeadLetterMessages = 1
    (for {
      buffer <- Ref[IO].of[Map[String, ActorRefs[IO]]](HashMap.empty[String, ActorRefs[IO]])
      deadLetterBus <- IO.ref(List.empty[Any])
      actorSystem <- ActorSystem[IO](
        "dead-letter-system",
        (msg: Any) =>
          msg match {
            case Debug(logSource, _, m @ DeadLetter(msg, _, _))
                if logSource.contains("Name:dead-letter") =>
              deadLetterBus.update(_ ++ List(m))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      deadLetter <- actorSystem.actorOf(
        PropsF[IO](DeadLetterActor().track("dead-letter-tracker")(buffer)),
        "dead-letter"
      )
      _ <- (1 to numberOfMessages).map(index => deadLetter ! s"test-$index").toList.parSequence_
      _ <- deadLetter ? [String] "kill"
      _ <- actorSystem.waitForIdle()
      _ <- (1 to numberOfDeadLetterMessages)
        .map(index => deadLetter ! s"dead-letter-$index")
        .toList
        .parSequence_
      _ <- deadLetter.waitForIdle
      actorMessages <- deadLetter.messageBuffer
      deadLetterMessages <- deadLetterBus.get
    } yield (actorMessages, deadLetterMessages, deadLetter)).unsafeToFuture().map {
      case (actorMessages, deadLetterMessages, deadLetter) =>
        actorMessages._2.toSet should be(
          (1 to numberOfMessages).map(index => s"test-$index").toSet ++ Set("kill")
        )

        deadLetterMessages.toSet should be(
          (1 to numberOfDeadLetterMessages)
            .map(index =>
              DeadLetter(
                Envelope(s"dead-letter-$index", None, Receiver(deadLetter)),
                None,
                Receiver(deadLetter)
              )
            )
            .toSet
        )
    }
  }

  it should "swap mailbox when the actor is killed and forward any messages to the dead letter mailbox. (no waiting) " in {
    val numberOfMessages = 100
    val numberOfDeadLetterMessages = 1
    (for {
      buffer <- Ref[IO].of[Map[String, ActorRefs[IO]]](HashMap.empty[String, ActorRefs[IO]])
      deadLetterBus <- IO.ref(List.empty[Any])
      actorSystem <- ActorSystem[IO](
        "dead-letter-system",
        (msg: Any) =>
          msg match {
            case Debug(logSource, _, m @ DeadLetter(msg, _, _))
                if logSource.contains("Name:dead-letter") =>
              deadLetterBus.update(_ ++ List(m))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      deadLetter <- actorSystem.actorOf(
        PropsF[IO](DeadLetterActor().track("dead-letter-tracker")(buffer)),
        "dead-letter"
      )
      _ <- (1 to numberOfMessages).map(index => deadLetter ! s"test-$index").toList.parSequence_
      _ <- deadLetter ? [String] "kill"
      _ <- (1 to numberOfDeadLetterMessages)
        .map(index => deadLetter ! s"dead-letter-$index")
        .toList
        .parSequence_
      _ <- deadLetter.waitForIdle
      actorMessages <- deadLetter.messageBuffer
      deadLetterMessages <- deadLetterBus.get
    } yield (actorMessages, deadLetterMessages, deadLetter)).unsafeToFuture().map {
      case (actorMessages, deadLetterMessages, deadLetter) =>
        actorMessages._2.toSet should be(
          (1 to numberOfMessages).map(index => s"test-$index").toSet ++ Set("kill")
        )

        deadLetterMessages.toSet should be(
          (1 to numberOfDeadLetterMessages)
            .map(index =>
              DeadLetter(
                Envelope(s"dead-letter-$index", None, Receiver(deadLetter)),
                None,
                Receiver(deadLetter)
              )
            )
            .toSet
        )
    }
  }
}
