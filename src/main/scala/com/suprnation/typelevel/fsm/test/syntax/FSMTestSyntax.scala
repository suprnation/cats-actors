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

package com.suprnation.typelevel.fsm.test.syntax

import cats.implicits._
import cats.{MonadThrow, Parallel}
import com.suprnation.actor.ReplyingActor
import com.suprnation.actor.fsm.FSM
import com.suprnation.actor.fsm.test.FSMTestKit

trait FSMTestSyntax {

  final implicit class FSMReplyingActorTestKit[F[+_]: Parallel: MonadThrow, Request, Response](
      actorRef: ReplyingActor[F, Request, Response]
  ) {

    def fsmTestKit[S, D]: F[FSMTestKit[F, S, D, Request, Response]] = actorRef match {
      case fsm: FSM[F, S, D, Request, Response] => FSMTestKit(fsm).pure[F]
      case _ => MonadThrow[F].raiseError(new IllegalArgumentException("Actor is not of type FSM"))
    }

  }
}
