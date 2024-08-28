package com.suprnation.fsm

import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Ref}
import cats.implicits.catsSyntaxOptionId
import com.suprnation.actor.{ActorSystem, ReplyingActor}
import com.suprnation.actor.fsm.FSM.Event
import com.suprnation.actor.fsm.{FSM, FSMConfig}
import com.suprnation.typelevel.actors.syntax.ActorSystemDebugOps
import com.suprnation.typelevel.fsm.syntax.FSMStateSyntaxOps
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.{DurationInt, FiniteDuration}

sealed trait Replies
case object WakingUp extends Replies
case object GotNudged extends Replies
case object StateTimeoutSleep extends Replies
case object TransitionTimeoutSleep extends Replies

sealed trait TimeoutState
case object Awake extends TimeoutState
case object Nudged extends TimeoutState
case object Asleep extends TimeoutState

sealed trait TimeoutRequest
case object StateSleep extends TimeoutRequest
case object TransitionSleep extends TimeoutRequest
case object Nudge extends TimeoutRequest
case class WakeUp(stayAwakeFor: Option[FiniteDuration] = None) extends TimeoutRequest

object TimeoutActor {

  def forMaxTimeoutActor(
      startWith: TimeoutState,
      defaultStateStayAwakeFor: FiniteDuration,
      timeOutDef: Deferred[IO, Boolean]
  ): IO[ReplyingActor[IO, TimeoutRequest, List[Replies]]] =
    FSM[IO, TimeoutState, Int, TimeoutRequest, Replies]
      .when(Awake, defaultStateStayAwakeFor, StateSleep)(sM => {
        case Event(StateSleep, _) =>
          timeOutDef.complete(true) *>
            sM.goto(Asleep).replying(StateTimeoutSleep)

        case Event(TransitionSleep, _) =>
          timeOutDef.complete(true) *>
            sM.goto(Asleep).replying(TransitionTimeoutSleep)

        case Event(Nudge, _) => sM.goto(Nudged).replying(GotNudged)
      })
      .when(Nudged)(sM => _ => sM.stayAndReply(GotNudged))
      .when(Asleep)(sM => {
        case Event(WakeUp(stayAwakeFor), _) =>
          sM.goto(Awake)
            .forMax(stayAwakeFor.map((_, TransitionSleep)))
            .replying(WakingUp)

        case Event(Nudge, _) => sM.goto(Nudged).replying(GotNudged)
      })
      .startWith(startWith, 0)
      .initialize

}

class TimeoutFSMSuite extends AsyncFlatSpec with Matchers {

  it should "timeout the Awake state using the 'forMax' and go back to sleep" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {

          timeOutDef <- Deferred[IO, Boolean]
          timeoutActor <- actorSystem.replyingActorOf(
            TimeoutActor.forMaxTimeoutActor(
              Asleep,
              3.seconds,
              timeOutDef
            )
          )

          r0 <- timeoutActor ? WakeUp(stayAwakeFor = 2.seconds.some)

          _ <- IO.race(
            IO.delay(fail("State did not time out after 4 seconds")).delayBy(4.seconds),
            timeOutDef.get.map(_ should be(true))
          )
          _ <- actorSystem.waitForIdle()
        } yield r0
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(List(WakingUp))
      }
  }

  it should "timeout the Awake state using the 'when' timeout and go back to sleep" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          timeOutDef <- Deferred[IO, Boolean]
          timeoutActor <- actorSystem.replyingActorOf(
            TimeoutActor.forMaxTimeoutActor(
              Asleep,
              3.seconds,
              timeOutDef
            )
          )

          r0 <- timeoutActor ? WakeUp()

          _ <- IO.race(
            IO.delay(fail("State did not time out after 4 seconds")).delayBy(4.seconds),
            timeOutDef.get.map(_ should be(true))
          )
          _ <- actorSystem.waitForIdle()
        } yield r0
      }
      .unsafeToFuture()
      .map { messages =>
        messages should be(List(WakingUp))
      }
  }

  it should "override state default timeout with the 'forMax' one" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          timeOutDef <- Deferred[IO, Boolean]
          timeoutActor <- actorSystem.replyingActorOf(
            TimeoutActor.forMaxTimeoutActor(
              Asleep,
              2.seconds,
              timeOutDef
            )
          )

          r0 <- timeoutActor ? WakeUp(stayAwakeFor = 3.seconds.some)

          _ <- IO.race(
            IO.delay(fail("State did not time out after 4 seconds")).delayBy(4.seconds),
            timeOutDef.get.map(_ should be(true))
          )
          _ <- actorSystem.waitForIdle()
        } yield r0
      }
      .unsafeToFuture()
      .map { messages =>
        messages should be(List(WakingUp))
      }
  }

  it should "not timeout once we move to another state" in {
    ActorSystem[IO]("FSM Actor")
      .use { actorSystem =>
        for {
          timeOutDef <- Deferred[IO, Boolean]
          timeoutActor <- actorSystem.replyingActorOf(
            TimeoutActor.forMaxTimeoutActor(
              Asleep,
              2.seconds,
              timeOutDef
            )
          )

          r0 <- timeoutActor ? WakeUp(stayAwakeFor = 2.seconds.some)
          r1 <- timeoutActor ? Nudge

          // IO.sleep should win here as the actor's timeout should be cancelled
          _ <- IO.race(
            IO.sleep(4.seconds),
            timeOutDef.get.map(_ => fail("State timed out but it should not."))
          )
          _ <- actorSystem.waitForIdle()
        } yield r0 ++ r1
      }
      .unsafeToFuture()
      .map { messages =>
        messages.toList should be(List(WakingUp, GotNudged))
      }
  }

}
