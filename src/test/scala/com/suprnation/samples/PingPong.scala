package com.suprnation.samples

import cats.effect.{ExitCode, IO, IOApp}
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.actor.{ActorSystem, ReplyingActor, ReplyingActorRef}

import scala.concurrent.duration._
import scala.language.postfixOps

object PingPong extends IOApp {

  trait PingPongMessages
  case class Ping(actorRef: ReplyingActorRef[IO, PingPongMessages, PingPongMessages])
      extends PingPongMessages
  case class Pong(actorRef: ReplyingActorRef[IO, PingPongMessages, PingPongMessages])
      extends PingPongMessages

  def pinger: ReplyingActor[IO, PingPongMessages, PingPongMessages] =
    new ReplyingActor[IO, PingPongMessages, PingPongMessages] {
      override def receive: ReplyingReceive[IO, PingPongMessages, PingPongMessages] = {
        case p @ Ping(sender) =>
          (IO.println("Received ping") >> (sender ! Pong(context.self)).delayBy(1 second)).as(p)
        case p @ Pong(sender) =>
          (IO.println("Received pong") >> (sender ! Ping(context.self)).delayBy(1 second)).as(p)
      }
    }

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]()
      .use(system =>
        for {
          ping <- system.replyingActorOf(pinger, "ping")
          pong <- system.replyingActorOf(pinger, "pong")

          _ <- ping ! Ping(pong)
          _ <- system.waitForTermination
        } yield ()
      )
      .as(ExitCode.Success)

}
