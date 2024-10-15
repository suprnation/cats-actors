package com.suprnation.actor.dungeon

import cats.effect.{Async, Fiber, Ref}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.dungeon.TimerSchedulerImpl._
import com.suprnation.actor.{ActorContext, Scheduler, SystemCommand, TimerScheduler}

import scala.concurrent.duration.FiniteDuration

/** INTERNAL API */
object TimerSchedulerImpl {
  sealed trait TimerMode {
    def repeat: Boolean
  }

  case object FixedDelayMode extends TimerMode {
    override def repeat: Boolean = true
  }

  case object SingleMode extends TimerMode {
    override def repeat: Boolean = false
  }

  final case class Timer[F[+_] : Async, Request, Key](
    key: Key,
    msg: Request,
    mode: TimerMode,
    generation: Int,
    owner: ActorRef[F, Request]
  )(scheduler: Scheduler[F]) extends SystemCommand {

    def schedule(
      actor: ActorRef[F, Request],
      timeout: FiniteDuration
    ): F[Fiber[F, Throwable, Unit]] =
      mode match {
        case SingleMode => scheduler.scheduleOnce_(timeout)(actor !* this)
        case FixedDelayMode =>
          scheduler.scheduleWithFixedDelay(timeout, timeout)(actor !* this)
      }
  }

  final case class StoredTimer[F[+_]](
    generation: Int,
    fiber: Fiber[F, Throwable, Unit]
  ) {
    def cancel: F[Unit] = fiber.cancel
  }
}

class TimerSchedulerImpl[F[+_] : Async, Request, Key](
  private val timerGen: Ref[F, Int],
  private val timerRef: Ref[F, Map[Key, StoredTimer[F]]],
  private val context: ActorContext[F, Request, Any]
) extends TimerScheduler[F, Request, Key] {

  private lazy val self: ActorRef[F, Request] = context.self

  override def startSingleTimer(key: Key, msg: Request, delay: FiniteDuration): F[Unit] =
    startTimer(key, msg, delay, SingleMode)

  def startTimerWithFixedDelay(key: Key, msg: Request, delay: FiniteDuration): F[Unit] =
    startTimer(key, msg, delay, FixedDelayMode)

  private def startTimer(
    key: Key,
    msg: Request,
    timeout: FiniteDuration,
    mode: TimerMode
  ): F[Unit] =
    for {
      gen <- timerGen.getAndUpdate(_ + 1)
      timer = Timer(key, msg, mode, gen, self)(context.system.scheduler)
      fiber <- timer.schedule(self, timeout)
      _ <- timerRef.flatModify(timers =>
        (
          timers + (key -> StoredTimer(gen, fiber)),
          timers.get(key).map(_.cancel).getOrElse(Async[F].unit)
        )
      )
    } yield ()

  def isTimerActive(key: Key): F[Boolean] = timerRef.get.map(_.contains(key))

  def cancel(key: Key): F[Unit] =
    timerRef.flatModify(timers =>
      (timers - key, timers.get(key).map(_.cancel).getOrElse(Async[F].unit))
    )

  def cancelAll: F[Unit] =
    timerRef.flatModify(timers =>
      (Map.empty, timers.view.values.toList.traverse_(_.cancel))
    )

}
