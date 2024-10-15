package com.suprnation.actor

import cats.effect.{Async, Ref}
import cats.implicits._
import com.suprnation.actor.Actor.{Actor, Receive}
import com.suprnation.actor.dungeon.TimerSchedulerImpl
import com.suprnation.actor.dungeon.TimerSchedulerImpl.{StoredTimer, Timer}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait Timers[F[+_], Request, Key] {
  _: Actor[F, Request] =>

  // to be removed with scala 3
  implicit def asyncEvidence: Async[F]
  protected val timerRef: Ref[F, Map[Key, StoredTimer[F]]]
  protected val timerGen: Ref[F, Int]

  private lazy val _timers = new TimerSchedulerImpl[F, Request, Key](timerGen, timerRef, context)
  final def timers: TimerScheduler[F, Request, Key] = _timers

  //timers.cancelAll()
//  abstract override def aroundPreRestart(reason: Option[Throwable], message: Option[Any]): F[Unit] = {
//    super.aroundPreRestart(reason, message)
//  }

  //timers.cancelAll()
//  override def aroundPostStop(): F[Unit] = super.aroundPostStop()

  // match message, if is timer, timers.interceptTimerMsg
  override def aroundReceive(receive: Receive[F, Request], msg: Any): F[Any] = interceptTimerMsg(msg)

  private def interceptTimerMsg(msg: Any)(implicit ct: ClassTag[Timer[F, Request, Key]]): F[Any] = {
    msg match {
      case t: Timer[F, Request, Key] =>
        if (!(t.owner eq self)) Async[F].unit
        else
          timerRef.get
            .map(timers => (timers contains t.key) && (timers(t.key).generation == t.generation))
            .ifM(
              timerGen.update(_ + 1) >>
                (if (!t.mode.repeat)
                  timerRef.update(_ - t.key)
                else
                  Async[F].unit) >> (self ! t.msg),
              Async[F].unit
            )

      case _ =>
        receive.applyOrElse(msg.asInstanceOf[Request], unhandled)
    }
  }
}

abstract class TimerScheduler[F[_], Request, Key] {

  /**
   * Start a timer that will send `msg` once to the `self` actor after
   * the given `timeout`.
   */
  def startSingleTimer(key: Key, msg: Request, delay: FiniteDuration): F[Unit]

  def startTimerWithFixedDelay(key: Key, msg: Request, delay: FiniteDuration): F[Unit]

  /**
   * Check if a timer with a given `key` is active.
   */
  def isTimerActive(key: Key): F[Boolean]

  /**
   * Cancel a timer with a given `key`.
   * If canceling a timer that was already canceled, or key never was used to start a timer
   * this operation will do nothing.
   */
  def cancel(key: Key): F[Unit]

  /**
   * Cancel all timers.
   */
  def cancelAll: F[Unit]

}