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

import cats.{Applicative, Monad, MonadThrow}
import cats.data.{EitherT, OptionT}
import cats.implicits._
import com.suprnation.actor.ActorRef.ActorRef
import com.suprnation.actor.ReplyingActorRef
import com.suprnation.actor.utils.Typechecking
import scala.reflect.{ClassTag, classTag}

trait TypecheckSyntax {
  final implicit class TypecheckFOps[F[_]: MonadThrow, R](objF: F[R]) {

    def narrowOpt[T: ClassTag]: OptionT[F, T] =
      OptionT.liftF(objF).flatMap(obj => Typechecking.typecheck[F, R, T](obj))

    def narrowOrElse[T: ClassTag, S](orElse: R => S): EitherT[F, S, T] =
      EitherT.liftF(objF).flatMap(obj => Typechecking.typecheckOr(obj, orElse))

    def narrowOrElseF[T: ClassTag, S](orElse: R => F[S]): EitherT[F, S, T] =
      EitherT.liftF(objF).flatMap(obj => Typechecking.typecheckOrF(obj, orElse))

    def narrow[T: ClassTag]: F[T] =
      objF.flatMap(obj =>
        Typechecking.typecheckOrRaise[F, R, T](obj, Typechecking.TypecheckException(_: R))
      )

  }

}

object TypecheckSyntax extends TypecheckSyntax
