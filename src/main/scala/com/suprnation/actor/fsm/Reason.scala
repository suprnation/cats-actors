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

package com.suprnation.actor.fsm

/** Reason why this FSM is shut down.
  */
sealed trait Reason

/** Default reason if calling `stop()`
  */
case object Normal extends Reason

/** Reason given when someone was calling `system.stop(fsm)` from outside; also applies to `Stop` supervision directive.
  */
case object Shutdown extends Reason

/** Signified that the FSM is shutting itself down because of an error, e.g. if the state to transition into does not exist. You can use this to communicate a more precise cause to the `onTermination` block.
  */
final case class Failure(cause: Any) extends Reason
