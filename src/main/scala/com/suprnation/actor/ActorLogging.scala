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

import com.suprnation.actor.ReplyingActor
import com.suprnation.actor.event.Debug

trait ActorLogging[F[+_], Request] {
  this: ReplyingActor[F, Request, ?] =>
  def log(msg: Any): F[Unit] = context.system.eventStream.offer(
    Debug(
      s"[Path: ${context.self.path}] [Name:${context.self.path.name}]",
      classOf[ReplyingActor[F, Nothing, Any]],
      msg
    )
  )
}
