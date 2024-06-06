package com.suprnation.samples.amqp

import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.props.Props
import com.suprnation.actor.{Actor, ActorSystem}

import scala.concurrent.duration._

object SingleActorRabbitApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val connectionConfig = AmqpConnectionFactoryConfig("localhost", "/", "guest", "guest")

    val resources = for {
      consumer <- Resource.pure(AmqpStream.amqpConsumerStream(connectionConfig))
      producer <- AmqpStream.amqpProducer(connectionConfig)
      actorSystem <- ActorSystem[IO]("cats-actors-rabbit-sample")
    } yield (consumer, producer, actorSystem)

    resources.use { case (consumer, producer, actorSystem) =>
      for {
        actor <- actorSystem.actorOf(Props(new Actor[IO] {
          override def receive: Receive[IO] = { case msg =>
            IO.println(msg)
          }
        }))
        _ <- consumer.evalTapChunk(actor ! _).compile.drain.start
        _ <- List("1", "2", "3", "4")
          .traverse(message => producer(message).delayBy(10.millis))
          .foreverM
          .start
        _ <- actorSystem.waitForTermination
      } yield ExitCode.Success
    }
  }
}
