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

package com.suprnation.actor.utils

import cats.{Applicative, Monad, MonadThrow}
import cats.data.{EitherT, OptionT}
import scala.reflect.{ClassTag, classTag}

object Typechecking {
  final case class TypecheckException[R, T: ClassTag](obj: R)
      extends IllegalStateException(
        s"Object not of expected type [${classTag[T].toString()}]: $obj"
      )

  def typecheck[F[_]: Applicative, R, T: ClassTag](obj: R): OptionT[F, T] =
    OptionT.when(classTag[T].runtimeClass.isInstance(obj))(obj.asInstanceOf[T])

  def typecheckOr[F[_]: Applicative, R, T: ClassTag, S](obj: R, orElse: R => S): EitherT[F, S, T] =
    EitherT.fromOptionF(typecheck[F, R, T](obj).value, orElse(obj))

  def typecheckOrF[F[_]: Monad, R, T: ClassTag, S](obj: R, orElse: R => F[S]): EitherT[F, S, T] =
    EitherT.fromOptionM(typecheck[F, R, T](obj).value, orElse(obj))

  def typecheckOrRaise[F[_]: MonadThrow, R, T: ClassTag](obj: R, orElse: R => Throwable): F[T] =
    typecheck[F, R, T](obj).getOrRaise(orElse(obj))

  def typecheckOrRaise[F[_]: MonadThrow, R, T: ClassTag](obj: R): F[T] =
    typecheckOrRaise(obj, (r: R) => Typechecking.TypecheckException(r))
}
