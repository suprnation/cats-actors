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

package com.suprnation.actor.dispatch.mailbox

trait Mailbox[F[+_], SystemMessage, A] {

  @inline def systemEnqueue(invocation: SystemMessage): F[Unit]

  @inline def enqueue(msg: A): F[Unit]

  @inline def dequeue: F[A]

  @inline def tryDequeue: F[Option[A]]

  @inline def hasMessage: F[Boolean]

  @inline def hasSystemMessage: F[Boolean]

  @inline def numberOfMessages: F[Int]

  @inline def suspendCount: F[Int]

  @inline def isSuspended: F[Boolean]

  @inline def isClosed: F[Boolean]

  @inline def resume: F[Unit]

  @inline def suspend: F[Unit]

  @inline def drainSystemQueue(onMessage: SystemMessage => F[Unit]): F[List[SystemMessage]]

  @inline def processMailbox(onSystemMessage: SystemMessage => F[Unit])(
      onUserMessage: A => F[Unit]
  ): F[Unit]

  def close: F[Unit]

  def cleanup(onMessage: Either[SystemMessage, A] => F[Unit]): F[Unit]

  /** Check whether the mailbox is idle.  (use this ideally only in tests) */
  @inline def isIdle: F[Boolean]
}
