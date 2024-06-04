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

package com.suprnation.typelevel.actors.syntax

import cats.effect.Async
import cats.effect.implicits.monadCancelOps_
import com.suprnation.actor.Actor

trait FActorSyntax {

  implicit final class FActorSyntaxOps[F[+_]: Async](actor: F[Actor[F]]) {

    def onErrorKillJVM: F[Actor[F]] =
      actor.guarantee(Async[F].delay {
        Runtime.getRuntime.halt(0)
      })

  }

}
