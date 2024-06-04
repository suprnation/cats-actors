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

import com.suprnation.actor.debug.TrackingActor

trait TrackingActorSyntax {
  implicit class TrackingActorFOps[F[+_]](fA: TrackingActor[F]) {

    def initCount: F[Int] = fA.initCountRef.get
    def preStartCount: F[Int] = fA.preStartCountRef.get
    def postStopCount: F[Int] = fA.postStopCountRef.get
    def preRestartCount: F[Int] = fA.preRestartCountRef.get
    def postRestartCount: F[Int] = fA.postRestartCountRef.get
    def preSuspendCount: F[Int] = fA.preSuspendCountRef.get
    def preResumeCount: F[Int] = fA.preResumeCountRef.get
    def messageBuffer: F[List[Any]] = fA.messageBufferRef.get
    def restartMessageBuffer: F[List[(Option[Throwable], Option[Any])]] =
      fA.restartMessageBufferRef.get
    def errorMessageBuffer: F[List[(Throwable, Option[Any])]] = fA.errorMessageBufferRef.get
  }
}
