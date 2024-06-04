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

package com.suprnation.actor

/** INTERNAL API
  *
  * Marker trait to show which Messages are automatically handled by the framework
  */
trait AutoReceivedMessage extends Serializable {}

/** Marker trait to indicate that a message might be potentially harmful.
  */
trait PossiblyHarmful

/** A message all Actors will understand, that when processed will terminate the Actor permanently.
  */
case object PoisonPill extends AutoReceivedMessage with PossiblyHarmful

/** A message all Actors will understand, that when processed will make the Actor throw an ActorKilledException which will trigger supervision.
  */
case object Kill extends AutoReceivedMessage with PossiblyHarmful

/** When Death Watch is used, the watcher will receive a Terminated(watched) message when watched is terminated.
  *
  * Terminated message can't be forwarded to another actor, since that actor might not be watching the subject. Instead, if you want to forward Terminated to another actor you should send the information in your own message.
  *
  * @param actor
  *   the watched actor that terminated.
  * @tparam F
  *   the effect type.
  */
case class Terminated[F[+_]](actor: ActorRef[F]) extends AutoReceivedMessage with PossiblyHarmful

/** When using ActorContext.setReceiveTimeout, the singleton instance of ReceiveTimeout will be sent to the Actor when there hasn't been any message for that long.
  */
case object ReceiveTimeout extends PossiblyHarmful
