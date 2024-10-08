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

package com.suprnation.actor.dungeon

import cats.syntax.all._
import com.suprnation.actor.engine.ActorCell

trait Suspension[F[+_], Request, Response] {
  self: ActorCell[F, Request, Response] =>

  def resumeNonRecursive: F[Unit] =
    (actorOp >>= (_.fold(asyncF.unit)(a => a.aroundPreResume()))) >>
      dispatchContext.mailbox.resume

  def suspendNonRecursive(causedByFailure: Option[Throwable]): F[Unit] =
    (for {
      maybeActor <- actorOp
      _ <- maybeActor match {
        case Some(actor) => actor.aroundPreSuspend(causedByFailure, self.currentMessage)
        case None        => asyncF.unit
      }
    } yield ()) >> dispatchContext.mailbox.suspend

}
