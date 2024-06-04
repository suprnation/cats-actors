package com.suprnation.actor.supervision

import cats.effect.{IO, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.Receive
import com.suprnation.actor.SupervisorStrategy.{Escalate, Restart, Resume, Stop}
import com.suprnation.actor.debug.TrackingActor
import com.suprnation.actor.props.PropsF
import com.suprnation.actor.{
  Actor,
  ActorRef,
  ActorSystem,
  AllForOneStrategy,
  OneForOneStrategy,
  SupervisionStrategy
}
import com.suprnation.typelevel.actors.syntax._

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.language.postfixOps

object Supervision {

  /** This is an example actor to demonstrate the Scala API.
    *
    * The last line demonstrates the essence of the error kernel design: spawn one-off actors which terminate after doing their job, pass on `sender()` to allow direct reply if that is what makes sense, or round-trip the sender as shown with the JobRequest/Job Reply message pair.
    */
  case class ExampleActor(
      override val supervisorStrategy: SupervisionStrategy[IO],
      _cache: Ref[IO, Map[String, TrackingActor.ActorRefs[IO]]],
      childrenActors: Ref[IO, List[ActorRef[IO]]],
      maxChildren: Int
  ) extends Actor[IO] {

    import Messages._

    implicit val cache: Ref[IO, Map[String, TrackingActor.ActorRefs[IO]]] = _cache

    override def preStart: IO[Unit] =
      List
        .tabulate(maxChildren)(index =>
          context.actorOf(
            PropsF(
              ReplyToMeWorker().track(s"reply-to-actor-$index")
            ),
            s"reply-to-actor-$index"
          )
        )
        .sequence >>=
        (children => childrenActors.set(children))

    override def receive: Receive[IO] = {
      case Dangerous(r, crash, index) =>
        childrenActors.get.map(_.apply(index)) >>= (_ ! JobRequest(r, self, crash))

      case JobReply(result, _) => result.pure[IO]
    }
  }

  case class ReplyToMeWorker() extends Actor[IO] {
    override def receive: Receive[IO] = {
      // After 1 second send the result of the intense computation
      case Messages.JobRequest(r, actor, shouldCrash) =>
        shouldCrash.fold(
          sender.fold(IO.unit)(s => actor ! Messages.JobReply(r, s))
        )(IO.raiseError)
    }
  }

  object Messages {
    case class Shutdown()

    case class Request(echoMessage: String)

    case class Dangerous(echoMessage: String, reason: Option[Throwable], index: Int = 0)

    case class JobRequest(echoMessage: String, sender: ActorRef[IO], reason: Option[Throwable])

    case class JobReply(echoMessage: String, originalSender: ActorRef[IO])
  }

  object ExampleActor {
    def oneForOneSupervisorStrategy: SupervisionStrategy[IO] =
      OneForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) {
        case _: ArithmeticException      => Resume
        case _: NullPointerException     => Restart
        case _: IllegalArgumentException => Stop
        case _: Exception                => Escalate
      }

    def allForOneSupervisionStrategy: SupervisionStrategy[IO] =
      AllForOneStrategy[IO](maxNrOfRetries = 3, withinTimeRange = 1 minute) {
        case _: ArithmeticException      => Resume
        case _: NullPointerException     => Restart
        case _: IllegalArgumentException => Stop
        case _: Exception                => Escalate
      }

    def apply(maxChildren: Int, supervisionStrategy: SupervisionStrategy[IO])(implicit
        system: ActorSystem[IO]
    ): IO[ActorRef[IO]] =
      (
        Ref[IO].of[Map[String, TrackingActor.ActorRefs[IO]]](
          HashMap.empty[String, TrackingActor.ActorRefs[IO]]
        ),
        Ref[IO].of(List.empty[ActorRef[IO]])
      ).flatMapN { (cache, childrenRefs) =>
        system.actorOf(
          PropsF(
            ExampleActor(supervisionStrategy, cache, childrenRefs, maxChildren).track("example")(
              cache
            )
          ),
          "example"
        )
      }
  }
}
