package com.suprnation.samples.amqp

import cats.effect._
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.props.{Props, PropsF}
import com.suprnation.actor.{Actor, ActorRef, ActorSystem}

import java.util.UUID
import scala.concurrent.duration._
object ShardedActorRabbitApp extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    case class ShardActor() extends Actor[IO] {
      override def receive: Receive[IO] = { case msg =>
        IO.println(s"[Actor: ${context.self.path.name}] [Message: $msg]")
      }
    }

    val connectionConfig = AmqpConnectionFactoryConfig("localhost", "/", "guest", "guest")

    val resources = for {
      consumer <- Resource.pure(AmqpStream.amqpConsumerStream(connectionConfig))
      producer <- AmqpStream.amqpProducer(connectionConfig)
      actorSystem <- ActorSystem[IO]("cats-actors-rabbit-sample")
    } yield (consumer, producer, actorSystem)

    resources.use { case (consumer, producer, actorSystem) =>
      for {
        actor <- actorSystem.actorOf(PropsF(for {
          children <- Ref[IO].of[List[ActorRef[IO]]](List.empty)
          index <- Ref[IO].of[Int](1)
          maxChildren = 10
        } yield new Actor[IO] {
          override def preStart: IO[Unit] =
            (1 to maxChildren).toList.traverse(index =>
              context.actorOf(Props(ShardActor()), s"child-$index")
            ) >>= children.set

          override def receive: Receive[IO] = { case m =>
            (children.get, index.get).flatMapN { case (children, currentIndex) =>
              (children(currentIndex % maxChildren) ! m) >> index.update(_ + 1)
            }
          }
        }))
        _ <- consumer.evalTapChunk(actor ! _).compile.drain.start
        _ <- IO
          .defer(producer(s"hello-${UUID.randomUUID().toString}").delayBy(1.millis))
          .foreverM
          .start
        _ <- actorSystem.waitForTermination
      } yield ExitCode.Success
    }
  }
}
