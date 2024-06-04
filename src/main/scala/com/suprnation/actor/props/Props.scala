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

package com.suprnation.actor.props

import cats.Applicative
import com.suprnation.actor.Actor

object PropsF {

  /** Create a Props actor with the given name and factory method where the factory method returns an effect-ful type.
    */
  def apply[F[+_]: Applicative](factory: => F[Actor[F]]): Props[F] =
    CreationFunctionProps[F](factory)
}
object Props {

  /** Create a Props actor with the given name and factory method.
    *
    * @tparam F
    *   the effect type.
    * @return
    *   the [[Actor]] wrapped in the effect typ.
    */
  def apply[F[+_]: Applicative](factory: => Actor[F]): Props[F] =
    PropsF(Applicative[F].pure(factory))
}

sealed trait Props[F[+_]] {
  val newActor: F[Actor[F]]
}

final case class CreationFunctionProps[F[+_]: Applicative](creationFunction: F[Actor[F]])
    extends Props[F] {

  override val newActor: F[Actor[F]] = creationFunction

}
