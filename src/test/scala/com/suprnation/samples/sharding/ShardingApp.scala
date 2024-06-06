package com.suprnation.samples.sharding

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.suprnation.EscalatingActor
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.props.Props
import com.suprnation.actor.{ActorRef, ActorSystem, PoisonPill}
import com.suprnation.samples.sharding.ShardingApp.ActorSupervisor.{ActorIdle, Shard}

import scala.concurrent.duration._
import scala.language.postfixOps
object ShardingApp extends IOApp {
  object ActorSupervisor {
    case class Shard(userId: Long, amount: Int)
    case class ActorIdle(userId: Long)
  }

  case class ActorSupervisor() extends EscalatingActor[IO] {
    override val receive: Receive[IO] = receiveWithMap(Map.empty)

    def receiveWithMap(userMap: Map[Long, ActorRef[IO]]): Receive[IO] = {
      case g @ Shard(userId, _) =>
        if (userMap.contains(userId)) {
          IO.println(s"User $userId is already found!") >> (userMap(userId) ! g)
        } else {
          for {
            _ <- IO.println(s"Oh noes! Actor $userId not found!!")
            actorAddress <- context.actorOf(Props(ShardActor(userId)))
            _ <- context.become(receiveWithMap(userMap + (userId -> actorAddress)))
            _ <- actorAddress >>! g
          } yield ()
        }

      case ActorIdle(userId) =>
        IO.println(s"Actor is idle $userId (parent)") >>
          context.become(receiveWithMap(userMap - userId)) >> (userMap(userId) ! PoisonPill)
    }
  }

  case class ShardActor(userId: Long) extends EscalatingActor[IO] {
    override def preStart: IO[Unit] =
      context.setReceiveTimeout(2 seconds)

    override def receive: Receive[IO] = {
      case Shard(userId, amount) =>
        IO.println(s"Received Message from child [UserId: $userId] [Amount: $amount]")
      case com.suprnation.actor.ReceiveTimeout =>
        IO.println(s"Hey no one sent me anything for [UserId: $userId] 2 second!") >>
          (context.parent ! ActorIdle(userId))
    }
  }

  override def run(args: List[String]): IO[ExitCode] =
    ActorSystem[IO]()
      .use(system =>
        for {
          supervisor <- system.actorOf(Props(new ActorSupervisor()))
          _ <- supervisor ! Shard(1, 10)
          _ <- supervisor ! Shard(2, 20)
          _ <- supervisor ! Shard(1, 5)
          _ <- supervisor ! Shard(2, 1)
          _ <- supervisor ! Shard(1, 10)
          _ <- supervisor ! Shard(2, 20)
          _ <- system.waitForTermination
        } yield ()
      )
      .as(ExitCode.Success)

}
