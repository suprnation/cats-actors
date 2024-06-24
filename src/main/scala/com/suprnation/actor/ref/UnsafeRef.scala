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

package com.suprnation.actor.ref

import cats.effect.Sync
import cats.implicits._

/** An implementation of the Ref interface but does not have any locks. Should be used in contexts where we are guaranteed to be thread-safe.
  */
class UnsafeRef[F[_]: Sync, A] private (private var value: A) {
  @inline def get: F[A] = Sync[F].delay(value)

  @inline def update(f: A => A): F[Unit] = Sync[F].delay {
    value = f(value)
  }

  @inline def updateAndGet(f: A => A): F[A] = Sync[F].delay {
    value = f(value)
    value
  }

  @inline def modify[B](f: A => (A, B)): F[B] = Sync[F].delay {
    val (newValue, result) = f(value)
    value = newValue
    result
  }

  @inline def flatModify[B](f: A => (A, F[B])): F[B] = for {
    tuple <- Sync[F].delay(f(value))
    (newValue, resultEffect) = tuple
    _ <- set(newValue)
    result <- resultEffect
  } yield result

  @inline def set(newValue: A): F[Unit] = Sync[F].delay {
    value = newValue
  }

}

object UnsafeRef {

  def of[F[_]: Sync, A](initial: A): F[UnsafeRef[F, A]] =
    Sync[F].delay(new UnsafeRef[F, A](initial))

}
