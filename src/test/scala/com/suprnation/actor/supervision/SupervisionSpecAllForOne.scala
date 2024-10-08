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

package com.suprnation.actor.supervision

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.suprnation.actor._
import com.suprnation.actor.event.Debug
import com.suprnation.actor.supervision.Supervision.ExampleActor.allForOneSupervisionStrategy
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class SupervisionSpecAllForOne extends AsyncFlatSpec with Matchers {
  import Supervision._

  def replyActor(suffix: Int): String = s"reply-to-actor-$suffix"

  it should "resume an actor (and drop the message) when a message causes an error.  " in {
    val crashData: Option[ArithmeticException] =
      Some(new ArithmeticException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(1, allForOneSupervisionStrategy)(system)
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount
      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childRestartMessageBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenMessageBuffers,
      childRestartMessageBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        println(trackedChildren)

        trackedChildren.size should be(1)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None),
          Messages.JobReply("4", parentReference)
        )
        parentInitCounts._2 should be(1)
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)

        childrenInitCounts should contain(replyActor(0) -> 1)
        childrenPreSuspensionCounts should contain(replyActor(0) -> 2)
        childrenPreResumeCounts should contain(replyActor(0) -> 2)
        childrenPreRestartCounts should contain(replyActor(0) -> 0)
        childrenPostStopCounts should contain(replyActor(0) -> 0)

        childrenMessageBuffers should contain(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None),
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          )
        )

        childrenRestartBuffers should contain(
          replyActor(0) -> List.empty
        )

        childrenErrorMessageBuffers should contain(
          replyActor(0) -> List(
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("1", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
            ),
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("3", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
            )
          )
        )

        parentErrorMessageBuffer._2 should be(List.empty)

        eventBuffer.size should be(2)
    }
  }

  it should "resume an actor (and drop the message) when a message causes an error [MULTIPLE ACTORS]  " in {
    val crashData: Option[ArithmeticException] =
      Some(new ArithmeticException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      // First actor should resume while the other actor should remain unchanged.
      exampleActor <- ExampleActor(4, allForOneSupervisionStrategy)(system)
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount
      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childRestartMessageBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenMessageBuffers,
      childRestartMessageBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        trackedChildren.size should be(4)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None),
          Messages.JobReply("4", parentReference)
        )
        parentInitCounts should be("example" -> 1)
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount should be("example" -> 0)
        parentResumeCount._2 should be(0)

        childrenInitCounts.toSet should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 1, replyActor(3) -> 1)
        childrenPreSuspensionCounts.toSet should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreResumeCounts.toSet should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreRestartCounts.toSet should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPostStopCounts.toSet should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)

        childrenMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None),
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          ),
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenRestartBuffers.toSet should contain.allOf(
          replyActor(0) -> List.empty,
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenErrorMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("1", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
            ),
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("3", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
            )
          ),
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        parentErrorMessageBuffer should be("example" -> List.empty)

        eventBuffer.size should be(2)
    }
  }

  it should "resume an actor (and drop the message) when a message causes an error [MULTIPLE ACTORS - SEND TO BOTH]  " in {
    val crashData: Option[ArithmeticException] =
      Some(new ArithmeticException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Warning(_, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      // First actor should resume while the other actor should remain unphased.
      exampleActor <- ExampleActor(4, allForOneSupervisionStrategy)(system)
      // Crash the actor!
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData, index = 1)
      _ <- exampleActor ! Messages.Dangerous("4", reason = None, index = 1)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount
      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childRestartMessageBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenMessageBuffers,
      childRestartMessageBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        println(trackedChildren)

        trackedChildren.size should be(4)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData, index = 1),
          Messages.Dangerous("4", reason = None, index = 1),
          Messages.JobReply("4", parentReference)
        )
        parentInitCounts should be("example" -> 1)
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount should be("example" -> 0)
        parentResumeCount should be("example" -> 0)

        childrenInitCounts.toSet should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 1, replyActor(3) -> 1)
        childrenPreSuspensionCounts.toSet should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreResumeCounts.toSet should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreRestartCounts.toSet should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPostStopCounts.toSet should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)

        childrenMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None)
          ),
          replyActor(1) -> List(
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          ),
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenRestartBuffers.toSet should contain.allOf(
          replyActor(0) -> List.empty,
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenErrorMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("1", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
            )
          ),
          replyActor(1) -> List(
            crashData.get -> Some(
              Envelope(
                Messages.JobRequest("3", parentReference, reason = crashData),
                parentReference
              )(Receiver(trackedChildren.find(x => x.path.name == replyActor(1)).get))
            )
          ),
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        parentErrorMessageBuffer should be("example" -> List.empty)

        eventBuffer.size should be(2)
    }
  }

  it should "restart an actor (and drop the message) when a message causes an error.  " in {
    // Null pointer exception will go into a restart.
    val crashData: Option[Throwable] =
      Some(new NullPointerException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Error(_, _, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(1, allForOneSupervisionStrategy)(system)

      // Crash the actor! This should restart the actor
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      // This will use the same actor as before...
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      // Crash the actor! This should restart the actor!
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      // This will use the same actor as before.
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount

      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenPostRestartCounts <- trackedChildren.traverse(_.postRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childrenRestartBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenPostRestartCounts,
      childrenMessageBuffers,
      childrenRestartBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenPostRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        trackedChildren.size should be(1)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None),
          Messages.JobReply("4", parentReference)
        )
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)
        parentInitCounts._2 should be(1)

        childrenInitCounts.toSet should contain(replyActor(0) -> 3)
        childrenPreSuspensionCounts.toSet should contain(replyActor(0) -> 2)
        childrenPreResumeCounts.toSet should contain(replyActor(0) -> 2)
        childrenPreRestartCounts.toSet should contain(replyActor(0) -> 2)
        childrenPostRestartCounts.toSet should contain(replyActor(0) -> 2)
        childrenPostStopCounts.toSet should contain(replyActor(0) -> 0)

        childrenMessageBuffers.toSet should contain(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None),
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          )
        )

        childrenRestartBuffers.toSet should contain(
          replyActor(0) -> List(
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              ),
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          )
        )

        childrenErrorMessageBuffers.toSet should contain(
          replyActor(0) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              ),
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          )
        )

        parentErrorMessageBuffer._2 should be(List.empty)
        eventBuffer.size should be(2)
    }
  }

  it should "restart an actor (and drop the message) when a message causes an error [MULTIPLE ACTORS].  " in {
    // Null pointer exception will go into a restart.
    val crashData: Option[Throwable] =
      Some(new NullPointerException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Error(_, _, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(4, allForOneSupervisionStrategy)(system)

      // Crash the actor! This should restart the actor
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      // This will use the same actor as before...
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      // Crash the actor! This should restart the actor!
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      // This will use the same actor as before.
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount

      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenPostRestartCounts <- trackedChildren.traverse(_.postRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childrenRestartBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenPostRestartCounts,
      childrenMessageBuffers,
      childrenRestartBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenPostRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        trackedChildren.size should be(4)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None),
          Messages.JobReply("4", parentReference)
        )
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)
        parentInitCounts._2 should be(1)

        childrenInitCounts should contain
          .allOf(replyActor(0) -> 3, replyActor(1) -> 3, replyActor(2) -> 3, replyActor(3) -> 3)
        childrenPreSuspensionCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPreResumeCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPreRestartCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPostRestartCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPostStopCounts should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)

        childrenMessageBuffers should contain.allOf(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None),
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          ),
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenRestartBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              ),
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          ),
          replyActor(1) -> List(
            crashData -> None,
            crashData -> None
          ),
          replyActor(2) -> List(
            crashData -> None,
            crashData -> None
          ),
          replyActor(3) -> List(
            crashData -> None,
            crashData -> None
          )
        )

        childrenErrorMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              ),
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          ),
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        parentErrorMessageBuffer._2 should be(List.empty)
        eventBuffer.size should be(2)
    }
  }

  it should "restart an actor (and drop the message) when a message causes an error from messages sent from multiple actors [MULTIPLE ACTORS].  " in {
    // Null pointer exception will go into a restart.
    val crashData: Option[Throwable] =
      Some(new NullPointerException(s"There was an error while computing your expression. "))
    (for {
      eventBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Error(_, _, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case _ => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(4, allForOneSupervisionStrategy)(system)

      // Crash the actor! This should restart the actor
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)
      // This will use the same actor as before...
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)

      // Crash the actor! This should restart the actor!
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData, index = 1)
      // This will use the same actor as before.
      _ <- exampleActor ! Messages.Dangerous("4", reason = None, index = 1)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount

      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor
      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenPostRestartCounts <- trackedChildren.traverse(_.postRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childrenRestartBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenPostRestartCounts,
      childrenMessageBuffers,
      childrenRestartBuffers,
      childrenErrorMessageBuffers,
      eventBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenPostRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer
          ) =>
        trackedChildren.size should be(4)
        parentMessageBuffer._2.size should be(6)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.JobReply("2", parentReference),
          Messages.Dangerous("3", reason = crashData, index = 1),
          Messages.Dangerous("4", reason = None, index = 1),
          Messages.JobReply("4", parentReference)
        )
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)
        parentInitCounts._2 should be(1)

        childrenInitCounts should contain
          .allOf(replyActor(0) -> 3, replyActor(1) -> 3, replyActor(2) -> 3, replyActor(3) -> 3)
        childrenPreSuspensionCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPreResumeCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPreRestartCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPostRestartCounts should contain
          .allOf(replyActor(0) -> 2, replyActor(1) -> 2, replyActor(2) -> 2, replyActor(3) -> 2)
        childrenPostStopCounts should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)

        childrenMessageBuffers should contain.allOf(
          replyActor(0) -> List(
            Messages.JobRequest("1", parentReference, reason = crashData),
            Messages.JobRequest("2", parentReference, reason = None)
          ),
          replyActor(1) -> List(
            Messages.JobRequest("3", parentReference, reason = crashData),
            Messages.JobRequest("4", parentReference, reason = None)
          ),
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        childrenRestartBuffers.toSet.size should be(4)
        val actor0Result = childrenRestartBuffers
          .collectFirst {
            case (actor, buffer) if actor == replyActor(0) => buffer.toSet
          }
          .getOrElse(Set.empty)

        actor0Result.size should be(2)
        // The order of the restart buffer can change (since its not deterministic - remember each actor has its own mailbox i.e. thread)
        actor0Result should be(
          Set(
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              ),
            crashData -> None
          )
        )

        val actor1Result = childrenRestartBuffers
          .collectFirst {
            case (actor, buffer) if actor == replyActor(1) => buffer.toSet
          }
          .getOrElse(Set.empty)

        actor1Result.size should be(2)
        actor1Result should be(
          Set(
            crashData ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(1)).get))
              ),
            crashData -> None
          )
        )

        childrenRestartBuffers should contain.allOf(
          replyActor(2) -> List(
            crashData -> None,
            crashData -> None
          ),
          replyActor(3) -> List(
            crashData -> None,
            crashData -> None
          )
        )

        childrenErrorMessageBuffers.toSet should contain.allOf(
          replyActor(0) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          ),
          replyActor(1) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("3", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(1)).get))
              )
          ),
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        parentErrorMessageBuffer._2 should be(List.empty)
        eventBuffer.size should be(2)
    }
  }

  it should "stop an actor (and drop the message) when a message causes an error.  " in {
    // Null pointer exception will go into a restart.
    val crashData: Option[Throwable] = Some(new IllegalArgumentException())
    (for {
      eventBus <- IO.ref(List.empty[Any])
      deadLetterBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Error(_, _, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case Debug(logSource, _, DeadLetter(msg, _, _)) => deadLetterBus.update(_ ++ List(msg))
            case _                                          => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(1, allForOneSupervisionStrategy)(system)

      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor

      // Crash the actor! This should restart the actor
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)

      // These will never be received....
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount

      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenPostRestartCounts <- trackedChildren.traverse(_.postRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childrenRestartBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get
      deadLetterBuffer <- deadLetterBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenPostRestartCounts,
      childrenMessageBuffers,
      childrenRestartBuffers,
      childrenErrorMessageBuffers,
      eventBuffer,
      deadLetterBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenPostRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer,
            deadLetterBuffer
          ) =>
        trackedChildren.size should be(1)
        parentMessageBuffer._2.size should be(4)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None)
        )
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)
        parentInitCounts._2 should be(1)

        childrenInitCounts should contain(replyActor(0) -> 1)
        childrenPreSuspensionCounts should contain(replyActor(0) -> 1)
        childrenPreResumeCounts should contain(replyActor(0) -> 0)
        childrenPreRestartCounts should contain(replyActor(0) -> 0)
        childrenPostRestartCounts should contain(replyActor(0) -> 0)
        childrenPostStopCounts should contain(replyActor(0) -> 1)

        // Restart buffer should be empty...
        childrenRestartBuffers should contain(
          replyActor(0) -> List.empty
        )

        // Error message will only contain the first one, the rest are empty.
        childrenErrorMessageBuffers should contain(
          replyActor(0) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          )
        )

        // parentErrorMessageBuffer should be(List.empty)
        eventBuffer.size should be(1)
        deadLetterBuffer.size should be(3)
        deadLetterBuffer should contain.allOf(
          Envelope(
            Messages.JobRequest("2", parentReference, reason = None),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          ),
          Envelope(
            Messages.JobRequest("3", parentReference, reason = crashData),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          ),
          Envelope(
            Messages.JobRequest("4", parentReference, reason = None),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          )
        )
    }
  }

  it should "stop an actor (and drop the message) when a message causes an error.  [MULTIPLE ACTORS]" in {
    // Null pointer exception will go into a restart.
    val crashData: Option[Throwable] = Some(new IllegalArgumentException())
    (for {
      eventBus <- IO.ref(List.empty[Any])
      deadLetterBus <- IO.ref(List.empty[Any])
      system <- ActorSystem[IO](
        "supervision-system",
        (msg: Any) =>
          msg match {
            case com.suprnation.actor.event.Error(_, _, clazz, msg)
                if clazz == classOf[AllForOneStrategy[IO]] =>
              eventBus.update(_ ++ List(msg))
            case Debug(logSource, _, DeadLetter(msg, _, _)) => deadLetterBus.update(_ ++ List(msg))
            case msg                                        => IO.unit
          }
      ).allocated.map(_._1)

      exampleActor <- ExampleActor(4, allForOneSupervisionStrategy)(system)
      trackedChildren <- exampleActor.allTrackedChildrenFromThisActor

      // Crash the actor! This should restart the actor
      _ <- exampleActor ! Messages.Dangerous("1", reason = crashData)

      // These will never be received....
      _ <- exampleActor ! Messages.Dangerous("2", reason = None)
      _ <- exampleActor ! Messages.Dangerous("3", reason = crashData)
      _ <- exampleActor ! Messages.Dangerous("4", reason = None)
      _ <- system.waitForIdle()

      parentReference = exampleActor
      parentMessageBuffer <- exampleActor.messageBuffer
      parentErrorMessageBuffer <- exampleActor.errorMessageBuffer
      parentInitCounts <- exampleActor.initCount

      parentSuspensionCount <- exampleActor.preSuspendCount
      parentResumeCount <- exampleActor.preResumeCount

      childrenInitCounts <- trackedChildren.traverse(_.initCount)
      childrenPreSuspensionCounts <- trackedChildren.traverse(_.preSuspendCount)
      childrenPreResumeCounts <- trackedChildren.traverse(_.preResumeCount)
      childrenPostStopCounts <- trackedChildren.traverse(_.postStopCount)
      childrenPreRestartCounts <- trackedChildren.traverse(_.preRestartCount)
      childrenPostRestartCounts <- trackedChildren.traverse(_.postRestartCount)
      childrenMessageBuffers <- trackedChildren.traverse(_.messageBuffer)
      childrenRestartBuffers <- trackedChildren.traverse(_.restartMessageBuffer)
      childrenErrorMessageBuffers <- trackedChildren.traverse(_.errorMessageBuffer)
      eventBuffer <- eventBus.get
      deadLetterBuffer <- deadLetterBus.get

    } yield (
      parentReference,
      parentMessageBuffer,
      parentErrorMessageBuffer,
      parentInitCounts,
      parentSuspensionCount,
      parentResumeCount,
      trackedChildren,
      childrenInitCounts,
      childrenPreSuspensionCounts,
      childrenPreResumeCounts,
      childrenPostStopCounts,
      childrenPreRestartCounts,
      childrenPostRestartCounts,
      childrenMessageBuffers,
      childrenRestartBuffers,
      childrenErrorMessageBuffers,
      eventBuffer,
      deadLetterBuffer
    )).unsafeToFuture().map {
      case (
            parentReference,
            parentMessageBuffer,
            parentErrorMessageBuffer,
            parentInitCounts,
            parentSuspensionCount,
            parentResumeCount,
            trackedChildren,
            childrenInitCounts,
            childrenPreSuspensionCounts,
            childrenPreResumeCounts,
            childrenPostStopCounts,
            childrenPreRestartCounts,
            childrenPostRestartCounts,
            childrenMessageBuffers,
            childrenRestartBuffers,
            childrenErrorMessageBuffers,
            eventBuffer,
            deadLetterBuffer
          ) =>
        trackedChildren.size should be(4)
        parentMessageBuffer._2.size should be(4)
        parentMessageBuffer._2.toSet should contain.allOf(
          Messages.Dangerous("1", reason = crashData),
          Messages.Dangerous("2", reason = None),
          Messages.Dangerous("3", reason = crashData),
          Messages.Dangerous("4", reason = None)
        )
        parentErrorMessageBuffer._2.size should be(0)
        parentSuspensionCount._2 should be(0)
        parentResumeCount._2 should be(0)
        parentInitCounts._2 should be(1)

        childrenInitCounts should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 1, replyActor(3) -> 1)
        childrenPreSuspensionCounts should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreResumeCounts should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPreRestartCounts should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPostRestartCounts should contain
          .allOf(replyActor(0) -> 0, replyActor(1) -> 0, replyActor(2) -> 0, replyActor(3) -> 0)
        childrenPostStopCounts should contain
          .allOf(replyActor(0) -> 1, replyActor(1) -> 1, replyActor(2) -> 1, replyActor(3) -> 1)

        // Restart buffer should be empty...
        childrenRestartBuffers should contain.allOf(
          replyActor(0) -> List.empty,
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        // Error message will only contain the first one, the rest are empty.
        childrenErrorMessageBuffers should contain.allOf(
          replyActor(0) -> List(
            crashData.get ->
              Some(
                Envelope(
                  Messages.JobRequest("1", parentReference, reason = crashData),
                  parentReference
                )(Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get))
              )
          ),
          replyActor(1) -> List.empty,
          replyActor(2) -> List.empty,
          replyActor(3) -> List.empty
        )

        eventBuffer.size should be(1)
        deadLetterBuffer.size should be(3)
        deadLetterBuffer should contain.allOf(
          Envelope(
            Messages.JobRequest("2", parentReference, reason = None),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          ),
          Envelope(
            Messages.JobRequest("3", parentReference, reason = crashData),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          ),
          Envelope(
            Messages.JobRequest("4", parentReference, reason = None),
            Option(parentReference),
            Receiver(trackedChildren.find(x => x.path.name == replyActor(0)).get)
          )
        )
    }
  }
}
