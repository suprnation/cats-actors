/*
 * Copyright 2024 SuprNation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.suprnation.actor

import cats.effect.Concurrent
import cats.implicits._
import cats.{Applicative, Monad, Parallel}
import com.suprnation.actor.ActorRef.NoSendActorRef
import com.suprnation.actor.SupervisorStrategy.{Decider, defaultDecider}
import com.suprnation.actor.event.Logging.LogLevel
import com.suprnation.actor.event.{Error, LogEvent, Logging}

import java.lang.reflect.InvocationTargetException
import scala.collection.immutable
import scala.concurrent.duration.Duration

object SupervisorStrategy {

  type Decider = PartialFunction[Throwable, Directive]

  /** When supervisorStrategy is not specified for an actor this `Decider` is used by default in the supervisor strategy.
    *
    * The child will be sopped when [[ActorInitializationException]], [[ActorKilledException]], or [[DeathPactException]] is thrown. It will be restarted for other `Exception` types.
    *
    * The error is escalated if it's a `Throwable`, i.e. `Error`.
    */
  final val defaultDecider: Decider = {
    case _: ActorInitializationException[?] => Stop
    case _: ActorKilledException            => Stop
    case _: DeathPactException[?]           => Stop
    case _: Exception                       => Restart
  }
  final val escalateDefault: Decider = (_: Any) => Escalate

  /** When supervisorStrategy is not specified for an actor this is used by default. OneForOneStrategy with decider defined in [[#defaultDecider]]
    */
  final def defaultStrategy[F[+_]: Monad]: SupervisionStrategy[F] =
    OneForOneStrategy()(defaultDecider)

  /** This strategy resembles Erlang in that failing children are always terminated (one-for-one)
    */
  final def stoppingStrategy[F[+_]: Monad]: SupervisionStrategy[F] = {
    def stoppingDecider: Decider = { case _: Exception =>
      Stop
    }

    OneForOneStrategy()(stoppingDecider)
  }

  def withinTimeRangeOption(withinTimeRange: Duration): Option[Duration] =
    if (withinTimeRange.isFinite && withinTimeRange >= Duration.Zero) Some(withinTimeRange)
    else None

  def maxNrOfRetriesOption(maxNrOfRetries: Int): Option[Int] =
    if (maxNrOfRetries < 0) None else Some(maxNrOfRetries)

  sealed trait Directive {
    def logLevel: LogLevel
  }

  case object Restart extends Directive {
    override def logLevel: LogLevel = Logging.ErrorLevel
  }

  case object Resume extends Directive {
    override def logLevel: LogLevel = Logging.WarningLevel
  }

  case object Stop extends Directive {
    override def logLevel: LogLevel = Logging.ErrorLevel
  }

  case object Escalate extends Directive {
    override def logLevel: LogLevel = throw new IllegalStateException("Escalate is not logged")
  }

}

/** An Akka SupervisorStrategy is the policy to apply for crashing children.
  *
  * <b>IMPORTANT</b>
  *
  * You should not normally need to create new subclasses, instead use the existing [[OneForOneStrategy]] or [[AllForOneStrategy]] but if you do, please read the docs of the methods below carefully, as incorrect implementation may lead to "blocked" actor system (i.e. permanently suspended actors).
  */
abstract class SupervisionStrategy[F[+_]: Monad] {

  import SupervisorStrategy._

  /** Returns the Decider that is associated with this SupervisionStrategy. The Decider is invoked by the default implementation of `handleFailure` to obtain the Directive to be applied.
    */
  def decider: Decider

  /** This method is called after the child has been remove from the set of children. It does not need to do anything special. Exceptions throw from this method do NOT make the actor fail if this happens during termination.
    */
  def handleChildTerminated(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      children: Iterable[NoSendActorRef[F]]
  ): F[Unit]

  /** This method is called to act on the failure of a child: restart if the flag is true, stop otherwise.
    */
  def processFailure(
      context: ActorContext[F, ?, ?],
      restart: Boolean,
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      stats: ChildRestartStats[F],
      children: immutable.List[ChildRestartStats[F]]
  ): F[Unit]

  /** This is the main entry point: in case of child's failure, this method must try to handle the failure by resuming, restart or stopping the child (and return `true`) or if it returns `false` to escalate the failure, which will lead to this actor re-throwing the exception which cause the failure. The exception will not be wrapped.
    *
    * @param children
    *   is a lazy collection (a view)
    */
  def handleFailure(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      cause: Throwable,
      stats: ChildRestartStats[F],
      children: immutable.List[ChildRestartStats[F]]
  ): F[Boolean] = {
    val directive: Directive = decider.applyOrElse(cause, SupervisorStrategy.escalateDefault)
    directive match {
      case Resume =>
        logFailure(context, child, cause.some, directive) >> resumeChild(child, cause.some) >> true
          .pure[F]
      case Restart =>
        logFailure(context, child, cause.some, directive) >>
          processFailure(context, restart = true, child, cause.some, stats, children) >> true
            .pure[F]
      case Stop =>
        logFailure(context, child, cause.some, directive) >>
          processFailure(context, restart = false, child, cause.some, stats, children) >> true
            .pure[F]
      case Escalate =>
        logFailure(context, child, cause.some, directive) >> false.pure[F]
    }
  }

  def logFailure(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      decision: Directive
  ): F[Unit] =
    if (loggingEnabled) {
      val logMessage: String = cause match {
        case Some(e: ActorInitializationException[?]) if e.getCause ne null =>
          e.getCause match {
            case ex: InvocationTargetException if ex.getCause ne null => ex.getCause.getMessage
            case ex                                                   => ex.getMessage
          }
        case Some(e: Throwable) => e.getMessage
        case _                  => "no underlying throwable"
      }
      decision match {
        case Escalate => Monad[F].unit
        case d =>
          if (d.logLevel == Logging.ErrorLevel)
            publish(
              context,
              Error(
                cause.getOrElse(Error.NoCause),
                child.path.toString,
                getClass,
                s"[Message: $logMessage]"
              )
            )
          else
            publish(
              context,
              LogEvent(d.logLevel, child.path.toString, getClass, s"[Message: $logMessage]")
            )
      }
    } else Monad[F].unit

  private def publish(context: ActorContext[F, ?, ?], logEvent: LogEvent): F[Unit] =
    context.system.eventStream.offer(logEvent)

  /** Logging of actor failure is done when this is `true`
    */
  protected def loggingEnabled: Boolean = true

  /** Resume, the previously failed child: <b>do never apply this to a child which is not the currently failing child</b>. Suspend/resume needs to be done in matching pairs, otherwise actors will wake up too soon or never at all.
    */
  final def resumeChild(child: NoSendActorRef[F], cause: Option[Throwable]): F[Unit] =
    child.asInstanceOf[InternalActorRef[F, Nothing, Any]].resume(cause)

  /** Restart the given child, possibly suspending it first.* <b>IMPORTRANT: </b> If the child is the currently failing one, it will already have been suspended, hence `suspendFirst` must be false. If the child is not the currently failing one, then it did not request this treatment and is therefore not prepared to be resumed without prior suspend.
    */
  final def restartChild(
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      suspendFirst: Boolean
  ): F[Unit] = {
    val c: InternalActorRef[F, Nothing, Any] = child.asInstanceOf[InternalActorRef[F, Nothing, Any]]
    (if (suspendFirst) c.suspend(cause) else Monad[F].unit) >> c.restart(cause)
  }
}

/** Applies the fault handling `Directive` (Resume, Restart, Stop) specified in the `Decider` to all children when one fails, as opposed to [[OneForOneStrategy]] that applies it only to the child actor failed.
  *
  * @param maxNrOfRetries
  *   the number of times a child actor is allowed to be restarted, negative value means no limit, if the limit is exceeded the child actor is stopped.
  * @param withinTimeRange
  *   duration of the time window for maxNrOfRetries, Duration.Inf means no window
  * @param decider
  *   map from Throwables which maps the given Throwables to restarts, otherwise escalates.
  * @param loggingEnabled
  *   the strategy logs the failure if this is enabled (true), by default it is enabled.
  */
case class AllForOneStrategy[F[+_]: Monad: Parallel](
    maxNrOfRetries: Int = -1,
    withinTimeRange: Duration = Duration.Inf,
    override val loggingEnabled: Boolean = true
)(val decider: SupervisorStrategy.Decider)
    extends SupervisionStrategy[F] {

  import SupervisorStrategy._

  /** This is a performance optimisation to avoid re-allocating the pairs upon every call to requestRestartPermission, assuming that strategies are shared across actors and thus this field does not take much space.
    */
  private val retriesWindow =
    (
      maxNrOfRetriesOption(maxNrOfRetries),
      withinTimeRangeOption(withinTimeRange).map(_.toMillis.toInt)
    )

  def handleChildTerminated(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      children: Iterable[NoSendActorRef[F]]
  ): F[Unit] = Monad[F].unit

  override def processFailure(
      context: ActorContext[F, ?, ?],
      restart: Boolean,
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      stats: ChildRestartStats[F],
      children: immutable.List[ChildRestartStats[F]]
  ): F[Unit] =
    if (children.nonEmpty) {
      if (restart && children.forall(_.requestRestartPermission(retriesWindow))) {
        children.parTraverse_(crs =>
          restartChild(crs.child, cause, suspendFirst = crs.child != child)
        )
      } else
        children.parTraverse_(c => context.stop(c.child))
    } else {
      Monad[F].unit
    }

}

/** Applies the fault handling `Directive` (Resume, Restart, Stop) specified in the `Decider` to the child actor that failed, as opposed to [[AllForOneStrategy]] that applies it to all children
  *
  * @param maxNrOfRetries
  *   the number of times a child actor is allowed to be restarted, negative value means no limit if the duration is infinite. If the limit is exceeded the child actor is stopped.
  * @param withinTimeRange
  *   duration of the time window for maxNrOfRetries, Duration.Inf means no window.
  * @param decider
  *   mapping from Throwable to [[com.suprnation.actor.SupervisorStrategy.Directive]].
  * @param loggingEnabled
  *   the strategy logs the failure if this is enable (true), by default it is enabled.
  */
case class OneForOneStrategy[F[+_]: Monad](
    maxNrOfRetries: Int = -1,
    withinTimeRange: Duration = Duration.Inf,
    override val loggingEnabled: Boolean = true
)(val decider: SupervisorStrategy.Decider)
    extends SupervisionStrategy[F] {

  private val retriesWindow = (
    SupervisorStrategy.maxNrOfRetriesOption(maxNrOfRetries),
    SupervisorStrategy.withinTimeRangeOption(withinTimeRange).map(_.toMillis.toInt)
  )

  def withMaxNrOfRetries(maxNrOfRetries: Int): OneForOneStrategy[F] =
    copy(maxNrOfRetries = maxNrOfRetries)(decider)

  override def handleChildTerminated(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      children: Iterable[NoSendActorRef[F]]
  ): F[Unit] = Applicative[F].unit

  override def processFailure(
      context: ActorContext[F, ?, ?],
      restart: Boolean,
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      stats: ChildRestartStats[F],
      children: immutable.List[ChildRestartStats[F]]
  ): F[Unit] =
    if (restart && stats.requestRestartPermission(retriesWindow))
      restartChild(child, cause, suspendFirst = false)
    else
      context.stop(child)

}

/** This is a specialised supervision strategy for the user guardian which stop the actor system.
  */
case class TerminateActorSystem[F[+_]: Concurrent: Parallel](
    system: ActorSystem[F]
) extends SupervisionStrategy[F] {

  def handleChildTerminated(
      context: ActorContext[F, ?, ?],
      child: NoSendActorRef[F],
      children: Iterable[NoSendActorRef[F]]
  ): F[Unit] = Concurrent[F].unit

  override def processFailure(
      context: ActorContext[F, ?, ?],
      restart: Boolean,
      child: NoSendActorRef[F],
      cause: Option[Throwable],
      stats: ChildRestartStats[F],
      children: immutable.List[ChildRestartStats[F]]
  ): F[Unit] =
    Concurrent[F].pure(cause.get.printStackTrace()) >> system.terminate(cause)

  /** Returns the Decider that is associated with this SupervisionStrategy. The Decider is invoked by the default implementation of `handleFailure` to obtain the Directive to be applied.
    */
  override def decider: Decider = defaultDecider
}
