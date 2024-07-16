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

import cats.Parallel
import cats.effect.{Concurrent, Temporal}
import com.suprnation.actor.{ActorLogging, ReplyingActor}

case class DeadLetterListener[F[+_]: Parallel: Concurrent: Temporal]()
    extends ReplyingActor[F, Any, Any]
    with ActorLogging[F, Any] {
  override def receive: PartialFunction[Any, F[Unit]] = { case message =>
    log(message)
  }
}
