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

package com.suprnation.actor.broadcast

import cats.Monoid
import cats.implicits._
import cats.effect.IO
import cats.effect.implicits._
import com.suprnation.actor.{ReplyingActor, ReplyingActorRef}
import com.suprnation.actor.Actor.ReplyingReceive
import com.suprnation.typelevel.actors.syntax.BroadcastSyntax._

import scala.collection.immutable.Queue
import scala.concurrent.duration.FiniteDuration

import TreenodeActor._

object TreenodeActor {

  sealed trait Response

  object Response {

    implicit def responseMonoid: Monoid[Response] = new Monoid[Response] {

      def combine(x: Response, y: Response): Response = (x, y) match {
        case (Pong(Queue()), _)         => y
        case (xPong: Pong, yPong: Pong) => xPong |+| yPong
        case _ => Fail(new Exception(s"Tried to combine responses $x and $y"))
      }

      def empty: Response = Pong.pongMonoid.empty
    }

  }

  case class Fail(t: Throwable) extends Response

  sealed trait Request extends Response
  case object Ping extends Request
  case class Pong(processedBy: Queue[String]) extends Request {
    def append(name: String): Pong = copy(processedBy = processedBy :+ name)
  }

  object Pong {

    implicit def pongMonoid: Monoid[Pong] = new Monoid[Pong] {

      def combine(x: Pong, y: Pong): Pong = Pong(x.processedBy ++ y.processedBy)

      def empty: Pong = Pong(Queue())
    }

  }

  case object Peng extends Request

  sealed trait TestMode
  case object Normal extends TestMode
  case object Error extends Exception("Test Error") with TestMode
  case object Wrong extends TestMode

}

case class TreenodeActor(
    name: String,
    delay: FiniteDuration,
    testMode: TreenodeActor.TestMode = TreenodeActor.Normal
)(
    children: ReplyingActorRef[
      IO,
      Ping.type,
      Response
    ]*
) extends ReplyingActor[IO, Ping.type, Response] {

  override def receive: ReplyingReceive[IO, Ping.type, Response] = { case Ping =>
    if (children.isEmpty)
      testMode match {
        case Normal => IO.sleep(delay) *> Pong(Queue(name)).pure[IO]
        case Error  => Fail(Error).pure[IO]
        case Wrong  => Peng.pure[IO]
      }
    else
      (children ? Ping)
        .mapping { case Fail(error) => IO.raiseError(error) }
        .expecting[Pong]
        .parallel
        .map(_.combineAll.append(name))
  }

}
