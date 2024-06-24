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

package com.suprnation.actor.dungeon

import cats.Applicative
import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import com.suprnation.actor.dungeon.ReceiveTimeout.ReceiveTimeoutContext

import scala.concurrent.duration.FiniteDuration

object ReceiveTimeout {
  case class ReceiveTimeoutContext[Request](
      receiveTimeout: Option[FiniteDuration],
      lastMessageTimestamp: Option[Long],
      message: Option[Request]
  )
}

class ReceiveTimeout[F[_]: Sync, Request](
    receiveTimeoutContextRef: Ref[F, ReceiveTimeout.ReceiveTimeoutContext[Request]]
) {

  def setReceiveTimeout(timeout: FiniteDuration, onTimeout: => Request): F[Unit] =
    receiveTimeoutContextRef.set(
      ReceiveTimeoutContext[Request](
        Some(timeout),
        Some(System.currentTimeMillis()),
        Some(onTimeout)
      )
    )

  def cancelReceiveTimeout: F[Unit] =
    receiveTimeoutContextRef.update(_.copy(receiveTimeout = None, message = None))

  def markLastMessageTimestamp: F[Unit] =
    receiveTimeoutContextRef.update { receiveTimeoutContext =>
      if (receiveTimeoutContext.receiveTimeout.isDefined) {
        receiveTimeoutContext.copy(lastMessageTimestamp = Some(System.currentTimeMillis()))
      } else {
        receiveTimeoutContext
      }
    }

  def checkTimeout(action: Request => F[Any]): F[Any] =
    receiveTimeoutContextRef.get.flatMap {
      case ReceiveTimeoutContext(Some(timeout), Some(timestamp), Some(message)) =>
        val timeoutTime: Long = timestamp + timeout.toMillis
        val currentTime: Long = System.currentTimeMillis()
        if (timeoutTime <= currentTime) {
          receiveTimeoutContextRef.set(
            ReceiveTimeout.ReceiveTimeoutContext(
              Some(timeout),
              Some(System.currentTimeMillis()),
              Some(message)
            )
          ) >> action(message)
        } else {
          Applicative[F].pure(())
        }
      case _ => Applicative[F].pure(())
    }
}
