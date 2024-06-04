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

package com.suprnation

import cats.Parallel
import cats.effect.std.Console
import cats.effect.{Async, Concurrent, Sync, Temporal}
import cats.implicits._
import com.suprnation.actor.SupervisorStrategy.Escalate
import com.suprnation.actor.{Actor, AllForOneStrategy, SupervisionStrategy}

import scala.concurrent.duration._
import scala.language.postfixOps

abstract class EscalatingActor[F[+_]: Console: Async: Parallel: Temporal] extends Actor[F] {
  override def supervisorStrategy: SupervisionStrategy[F] =
    AllForOneStrategy[F](maxNrOfRetries = 0, withinTimeRange = 1 minute) { case _: Throwable =>
      Escalate
    }

  override def preSuspend(reason: Option[Throwable], message: Option[Any]): F[Unit] =
    (reason, message) match {
      // This is the actor that was directly responsible for the problem.  The message is filled in so we know that this actor is the cause.
      case (Some(t), Some(m)) =>
        Console[F].println(
          s"Actor [${this.self.path}] has been suspended due [Error: $t] [Message: $m]"
        ) >>
          Sync[F].blocking(t.printStackTrace())
      case _ => Concurrent[F].unit
    }

}
