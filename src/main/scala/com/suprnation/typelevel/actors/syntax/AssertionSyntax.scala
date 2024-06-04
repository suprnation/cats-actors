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

import cats.effect.MonadCancelThrow
import cats.implicits._

trait AssertionSyntax {

  implicit class AssertionFOps[F[_]: MonadCancelThrow, A](fA: F[A]) {

    @inline final def assert(condition: A => F[Boolean], message: A => F[String]): F[Unit] =
      fA.flatMap { a =>
        condition(a).ifM(
          MonadCancelThrow[F].unit,
          message(a).flatMap(m => MonadCancelThrow[F].raiseError[Unit](new AssertionError(m)))
        )
      }

  }

  implicit class AssertionOps[F[_]: MonadCancelThrow, A](a: A) {

    @inline final def assert(condition: A => F[Boolean], message: A => F[String]): F[Unit] =
      condition(a).ifM(
        MonadCancelThrow[F].unit,
        message(a).flatMap(m => MonadCancelThrow[F].raiseError[Unit](new AssertionError(m)))
      )

  }

}
