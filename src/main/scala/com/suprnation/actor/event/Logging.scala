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

package com.suprnation.actor.event

import com.suprnation.actor.event.Logging._

object Logging {

  /** Log level in numeric form, used when deciding whether a certain log statement should generate a log event. Predefined levels are ErrorLevel (1) to DebugLevel (4). In case you want to add more levels, loggers need to be subscribed to their event bus channels manually.
    */
  final val ErrorLevel = LogLevel(1)
  final val WarningLevel = LogLevel(2)
  final val InfoLevel = LogLevel(3)
  final val DebugLevel = LogLevel(4)

  def stackTraceFor(e: Throwable): String = e match {
    case null | Error.NoCause => ""
    case _: NoStackTrace      => s" (${e.getClass.getName}: ${e.getMessage})"
    case other =>
      val sw = new java.io.StringWriter
      val pw = new java.io.PrintWriter(sw)
      pw.append('\n')
      other.printStackTrace(pw)
      sw.toString
  }

  final case class LogLevel(asInt: Int) extends AnyVal {
    @inline def >=(other: LogLevel): Boolean = asInt >= other.asInt

    @inline def <=(other: LogLevel): Boolean = asInt <= other.asInt

    @inline def >(other: LogLevel): Boolean = asInt > other.asInt

    @inline def <(other: LogLevel): Boolean = asInt < other.asInt
  }
}

sealed trait LogEvent {

  /** When this LogEvent was created according to System.currentTimeMillis
    */
  val timestamp: Long = System.currentTimeMillis

  /** The LogLevel of this LogEvent
    */
  def level: LogLevel

  /** The source of this event
    */
  def logSource: String

  /** The class of the source of this event
    */
  def logClass: Class[?]

  /** The message, may be any object or null.
    */
  def message: Any
}

object LogEvent {
  def apply(level: LogLevel, logSource: String, logClass: Class[?], message: Any): LogEvent =
    level match {
      case ErrorLevel   => Error(logSource, logClass, message)
      case WarningLevel => Warning(logSource, logClass, message)
      case InfoLevel    => Info(logSource, logClass, message)
      case DebugLevel   => Debug(logSource, logClass, message)

      case level => throw new IllegalArgumentException(s"Unsupported log level [$level]")
    }
}

trait LogEventWithCause {
  def cause: Throwable
}

object Error {

  def apply(logSource: String, logClass: Class[?], message: Any): Error =
    new Error(NoCause, logSource, logClass, message)

  /** Null Object used for errors without cause Throwable */
  object NoCause extends NoStackTrace
}

case class Error(
    override val cause: Throwable,
    logSource: String,
    logClass: Class[?],
    message: Any = ""
) extends LogEvent
    with LogEventWithCause {
  def this(logSource: String, logClass: Class[?], message: Any) =
    this(Error.NoCause, logSource, logClass, message)

  override def level: LogLevel = ErrorLevel

}

case class Warning(logSource: String, logClass: Class[?], message: Any = "") extends LogEvent {
  override def level: LogLevel = WarningLevel
}

case class Info(logSource: String, logClass: Class[?], message: Any = "") extends LogEvent {
  override def level: LogLevel = InfoLevel
}

case class Debug(logSource: String, logClass: Class[?], message: Any = "") extends LogEvent {
  override def level: LogLevel = DebugLevel
}

trait NoStackTrace extends Throwable {
  override def fillInStackTrace(): Throwable =
    if (scala.util.control.NoStackTrace.noSuppression) super.fillInStackTrace()
    else this
}
