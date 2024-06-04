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

package com.suprnation.actor.dispatch

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.all._

object MessageQueue {
  def unbounded[F[+_]: Concurrent, A]: F[MessageQueue[F, A]] =
    for {
      queue <- Queue.unbounded[F, A]
    } yield new MessageQueue[F, A] {

      def enqueue(msg: A): F[Unit] =
        queue.offer(msg)

      def dequeue: F[A] =
        queue.take

      def tryDequeue: F[Option[A]] =
        queue.tryTake

      def numberOfMessages: F[Int] =
        queue.size

      def hasMessage: F[Boolean] =
        queue.size.map(_ > 0)

      def cleanup(onMessage: A => F[Unit]): F[Unit] = for {
        messages <- queue.tryTakeN(None)
        _ <- messages.traverse_(message => onMessage(message))
      } yield ()

    }
}

/** A MessageQueue is one of the core components in forming a mailbox. The MessageQueue is where the normal messages that are send to Actors will be enqueued (and subsequently dequeued)
  *
  * It needs to at least support N producers and 1 consumer thread-safely.
  */
trait MessageQueue[F[+_], A] {

  /** Try to enqueue the message to this queue, or throw an exception.
    */
  def enqueue(msg: A): F[Unit]

  /** Dequeue the next message from this queue, return null failing that.
    */
  def dequeue: F[A]

  /** Try to dequeue the next message from this queue, return null failing that.
    */
  def tryDequeue: F[Option[A]]

  /** Should return the current number of messages held in the queue.
    */
  def numberOfMessages: F[Int]

  /** Indicates whether this queue is non-empty.
    */
  def hasMessage: F[Boolean]

  /** Called when the mailbox this queue belongs to is disposed of. Normally it is expected to transfer all remaining messages into the letter queue which is passed in. The owner of this MessageQueue is passed if available, "/deadletters" otherwise
    */
  def cleanup(onMessage: A => F[Unit]): F[Unit]

}
