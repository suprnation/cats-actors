package com.suprnation.actor

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.{Actor, Receive, ReplyingReceive}
import com.suprnation.actor.test.TestKit
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

class TellAskExample extends AsyncFlatSpec with Matchers with TestKit {

  "combining tell and ask" should "not hang" in {
    import AskTellBug._

    ActorSystem[IO]()
      .use({ actorSystem =>
        for {
          ref <- actorSystem.actorOf(AskParentActor(childSleepTime = 1.second))
          _ <- ref ! Hey
          _ <- IO.sleep(200.millis) // sleep < 1 second (child)
        } yield true
      })
      .unsafeRunSync() shouldEqual true
  }

}

object AskTellBug {
  sealed trait Req
  case object Hey extends Req

  case class AskChildActor(sleepTime: FiniteDuration) extends Actor[IO, Req] {
    override def receive: Receive[IO, Req] = { case Hey =>
      IO.println(s"Hey received by child. Sleeping for $sleepTime") >> IO.sleep(sleepTime)
    }
  }

  case class AskParentActor(childSleepTime: FiniteDuration)
    extends ReplyingActor[IO, Req, Any] {

    override def receive: ReplyingReceive[IO, Req, Any] = { case msg =>
      IO.println("Hey received by parent. Asking child") >>
        context.replyingActorOf(AskChildActor(childSleepTime))
          .flatMap(_ ? msg)
          .flatTap(_ => IO.println("Ask response received by parent"))
    }
  }
}
