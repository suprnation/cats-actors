package com.suprnation.actor.supervision

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.SupervisorStrategy.Escalate
import com.suprnation.actor._
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.supervision.SupervisionEscalation.{
  ChildActor,
  GrandParent,
  ParentActor,
  ThrowingTantrumActor
}
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.language.postfixOps

object SupervisionEscalation {

  case class GrandParent(numberOfChildren: Int) extends Actor[IO, String] {
    val children: Ref[IO, List[ReplyingActorRef[IO, String, Any]]] = Ref.unsafe(List.empty)

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ =>
        println(s"Trying to recover in ${context.self.path}"); Escalate
      }

    override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
      IO.println(s"Suspending ${context.self.path}")

    override def preStart: IO[Unit] =
      Range
        .inclusive(1, numberOfChildren)
        .map(index => context.actorOf[String](ParentActor(numberOfChildren), s"parent-$index"))
        .toList
        .sequence
        .flatMap(children.set)

    override def receive: Receive[IO, String] = { case _ =>
      children.get.flatMap(_.head ! "die")
    }

  }

  case class ParentActor(numberOfChildren: Int) extends Actor[IO, String] {
    val children: Ref[IO, List[ActorRef[IO, String]]] = Ref.unsafe(List.empty)

    override def supervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) { case _ =>
        println(s"Trying to recover in ${context.self.path}"); Escalate
      }

    override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
      IO.println(s"Suspending ${context.self.path}")

    override def preStart: IO[Unit] =
      Range
        .inclusive(1, numberOfChildren)
        .map { index =>
          context.actorOf[String](ThrowingTantrumActor(), s"child-$index")
        }
        .toList
        .sequence
        .flatMap(children.set)

    override def receive: Receive[IO, String] = { case _ =>
      children.get.flatMap(_.head ! "die")
    }

  }

  case class ChildActor() extends Actor[IO, String] {
    override def receive: Receive[IO, String] = { case msg =>
      IO.println(s"[Actor: ${context.self.path}] Pre processing message $msg") >> IO.sleep(
        1 second
      ) >> IO.pure(msg)
    }
  }

  case class ThrowingTantrumActor() extends Actor[IO, String] {

    override def receive: Receive[IO, String] = { case _ =>
      IO.raiseError(new RuntimeException("I don't want to work!"))
    }

    override def preSuspend(reason: Option[Throwable], message: Option[Any]): IO[Unit] =
      IO.println(s"Suspending ${context.self.path}")

    override def preResume: IO[Unit] = IO.println("Resuming")
  }
}

class SupervisionEscalation extends AsyncFlatSpec with Matchers {

  def replyActor(suffix: Int): String = s"reply-to-actor-$suffix"

  it should "kill the actor system when an actor escalates an error (single actor).  " in {
    (for {
      cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
        HashMap.empty[String, TrackingActor.ActorRefs[IO]]
      )
      eventBus <- IO.ref(List.empty[Any])
      tuple <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case msg => IO.println(msg)
          }
      ).allocated
      (system, waitForSystemStop) = tuple

      exampleActor <- system.actorOf(
        ThrowingTantrumActor().track("tantrum-actor-tracker")(cache)
      )
      // Crash the actor!
      _ <- IO.race(IO.sleep(1 second), waitForSystemStop)
      _ <- IO.println("System is running")
      _ <- exampleActor ! "please work!"
      _ <- waitForSystemStop

    } yield (
    )).unsafeToFuture().map { case () =>
      1 should be(1)
    }
  }

  it should "kill the actor system and all actors when system is shutting down" in {
    (for {
      cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
        HashMap.empty[String, TrackingActor.ActorRefs[IO]]
      )
      eventBus <- IO.ref(List.empty[Any])
      tuple <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case msg => IO.println(msg)
          }
      ).allocated
      (system, waitForSystemStop) = tuple

      exampleActor <- system.actorOf(
        ThrowingTantrumActor().track("tantrum-actor-tracker")(cache)
      )
      childActor1 <- system.actorOf[String](ChildActor(), "child-actor-1")
      childActor2 <- system.actorOf[String](ChildActor(), "child-actor-2")
      childActor3 <- system.actorOf[String](ChildActor(), "child-actor-3")

      // Crash the actor!
      _ <- childActor1 ! "hello"
      _ <- IO.race(IO.sleep(1 second), waitForSystemStop)
      _ <- childActor2 ! "hello"
      _ <- IO.race(IO.sleep(1 second), waitForSystemStop)
      _ <- childActor3 ! "hello"
      _ <- exampleActor ! "please work!"
      _ <- waitForSystemStop
    } yield (
    )).unsafeToFuture().map { case () =>
      1 should be(1)
    }
  }

  it should "if a single actor dies in a (1 level) hierarchy we should kill the whole hierarchy. " in {
    (for {
      cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
        HashMap.empty[String, TrackingActor.ActorRefs[IO]]
      )
      eventBus <- IO.ref(List.empty[Any])
      tuple <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case msg => IO.println(msg)
          }
      ).allocated

      (system, waitForSystemStop) = tuple

      grandParent <- system.actorOf(ParentActor(5).track("tantrum-actor-tracker")(cache))
      _ <- IO.race(IO.sleep(2 second), waitForSystemStop)
      _ <- grandParent ! "die nannu"
      _ <- waitForSystemStop
    } yield (
    )).unsafeToFuture().map { case () =>
      1 should be(1)
    }
  }

  it should "if a single actor dies in a (2 level) hierarchy we should kill the whole hierarchy. " in {
    (for {
      cache <- Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
        HashMap.empty[String, TrackingActor.ActorRefs[IO]]
      )
      eventBus <- IO.ref(List.empty[Any])
      tuple <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case msg => IO.println(msg)
          }
      ).allocated

      (system, waitForSystemStop) = tuple

      grandParent <- system.actorOf[String](GrandParent(5), "grand-parent")
      _ <- IO.race(IO.sleep(2 second), waitForSystemStop)
      _ <- grandParent ! "die nannu"
      _ <- waitForSystemStop
    } yield (
    )).unsafeToFuture().map { case () =>
      1 should be(1)
    }
  }

  it should "terminate the Actor System when an ActorInitializationException is thrown with default supervision" in {
    ActorSystem[IO]()
      .use(actorSystem =>
        actorSystem
          .replyingActorOf(
            new Actor[IO, String] {
              override def preStart: IO[Unit] = IO.raiseError(new RuntimeException("Don't start"))
            },
            "robot"
          )
          .map(_ => succeed)
          .recover { case _ =>
            fail("replyingActorOf should never throw an ActorInitializationException")
          }
      )
      .recover { case exception: ActorInitializationException[IO] =>
        exception.cause mustBe a[RuntimeException]
      }
      .unsafeToFuture()
  }

}
