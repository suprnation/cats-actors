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

import cats.effect.std.{Console, Supervisor}
import cats.effect.{Concurrent, Deferred, Fiber}
import cats.implicits._

trait ShutdownHookSyntax {
  implicit class ShudownHookSyntaxOps[F[+_]: Concurrent: Console](
      fiberF: F[Fiber[F, Throwable, Unit]]
  ) {
    def hookToTermination(
        shutdownSignal: Deferred[F, Unit],
        supervisor: Supervisor[F]
    ): F[Fiber[F, Throwable, Unit]] =
      fiberF.flatMap(fiber => supervisor.supervise(shutdownSignal.get >> fiber.cancel))
  }

}
