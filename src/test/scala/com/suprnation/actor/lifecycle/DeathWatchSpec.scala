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

package com.suprnation.actor.lifecycle

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.suprnation.actor.Actor.Actor
import com.suprnation.actor.ActorRef.{ActorRef, NoSendActorRef}
import com.suprnation.actor._
import com.suprnation.actor.engine.ActorCell
import com.suprnation.actor.lifecycle.Lifecycle.WatchContext
import com.suprnation.typelevel.actors.syntax._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class DeathWatchSpec extends AsyncFlatSpec with Matchers {

  sealed trait DeathWatchRequest
  case class Message(message: String) extends DeathWatchRequest
  case class Terminated(actorRef: NoSendActorRef[IO], correlationId: String)
      extends DeathWatchRequest

  it should "be able to monitor actor in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 1", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(watcher)(Actor.empty[IO, DeathWatchRequest], "watchee")

      waitAll = List(watcher, watchee).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)
    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching should have size 1
        (watcherWatchContext.watching should contain).key(watcheeWatchContext.actor)
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy should have size 1
        watcheeWatchContext.watchedBy should contain(watcherWatchContext.actor)
    }
  }

  it should "be able to monitor actors in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 2", (_: Any) => IO.unit).allocated.map(_._1)
      grandParent <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "grandParent")
      parent <- createChild(grandParent)(Actor.empty[IO, DeathWatchRequest], "parent")
      child1 <- createChild(parent)(Actor.empty, "child1")
      child2 <- createChild(parent)(Actor.empty, "child2")
      _ <- parent.cell
      _ <- grandParent.cell

      _ <- watch(grandParent, parent)
      _ <- watch(grandParent, child1)
      _ <- watch(parent, child1)
      _ <- watch(parent, child2)

      _ <- List(grandParent, parent, child1, child2).waitForIdle

      grandParentWatchContext <- getWatchContext(grandParent)
      parentWatchContext <- getWatchContext(parent)
      child1WatchContext <- getWatchContext(child1)
      child2WatchContext <- getWatchContext(child2)
    } yield (grandParentWatchContext, parentWatchContext, child1WatchContext, child2WatchContext))
      .unsafeToFuture()
      .map {
        case (
              grandParentWatchContext,
              parentWatchContext,
              child1WatchContext,
              child2WatchContext
            ) =>
          grandParentWatchContext.watching should have size 2
          (grandParentWatchContext.watching should contain).key(parentWatchContext.actor)
          (grandParentWatchContext.watching should contain).key(child1WatchContext.actor)
          grandParentWatchContext.watchedBy shouldBe empty

          parentWatchContext.watching should have size 2
          (parentWatchContext.watching should contain).key(child1WatchContext.actor)
          (parentWatchContext.watching should contain).key(child2WatchContext.actor)
          parentWatchContext.watchedBy should have size 1
          parentWatchContext.watchedBy should contain(grandParentWatchContext.actor)

          child1WatchContext.watching shouldBe empty
          child1WatchContext.watchedBy should have size 2
          child1WatchContext.watchedBy should contain(grandParentWatchContext.actor)

          child2WatchContext.watching shouldBe empty
          child2WatchContext.watchedBy should have size 1
          child1WatchContext.watchedBy should contain(parentWatchContext.actor)
          child2WatchContext.watchedBy should contain(parentWatchContext.actor)
      }
  }

  it should "be able to monitor parent actor in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 3", (_: Any) => IO.unit).allocated.map(_._1)
      grandParent <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "grandParent")
      parent <- createChild(grandParent)(Actor.empty[IO, DeathWatchRequest], "parent")

      _ <- watch(parent, grandParent)

      _ <- List(grandParent, parent).waitForIdle

      grandParentWatchContext <- getWatchContext(grandParent)
      parentWatchContext <- getWatchContext(parent)
    } yield (grandParentWatchContext, parentWatchContext)).unsafeToFuture().map {
      case (grandParentWatchContext, parentWatchContext) =>
        grandParentWatchContext.watching shouldBe empty
        grandParentWatchContext.watchedBy should have size 1
        grandParentWatchContext.watchedBy should contain(parentWatchContext.actor)

        parentWatchContext.watching should have size 1
        (parentWatchContext.watching should contain).key(grandParentWatchContext.actor)
        parentWatchContext.watchedBy shouldBe empty
    }
  }

  it should "be able to monitor actor in different hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 4", (_: Any) => IO.unit).allocated.map(_._1)
      sol <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "sol")
      centauri <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "alphaCentauri")

      _ <- watch(sol, centauri)

      _ <- List(sol, centauri).waitForIdle

      solWatchContext <- getWatchContext(sol)
      centauriWatchContext <- getWatchContext(centauri)
    } yield (solWatchContext, centauriWatchContext)).unsafeToFuture().map {
      case (solWatchContext, centauriWatchContext) =>
        solWatchContext.watching should have size 1
        (solWatchContext.watching should contain).key(centauriWatchContext.actor)
        solWatchContext.watchedBy shouldBe empty

        centauriWatchContext.watching shouldBe empty
        centauriWatchContext.watchedBy should have size 1
        centauriWatchContext.watchedBy should contain(solWatchContext.actor)
    }
  }

  it should "be able to monitor actors in different hierarchies" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 5", (_: Any) => IO.unit).allocated.map(_._1)
      sol <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "sol")
      earth <- createChild(sol)(Actor.empty[IO, DeathWatchRequest], "earth")

      centauri <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "alphaCentauri")
      proximaB <- createChild(centauri)(Actor.empty[IO, DeathWatchRequest], "proximaB")
      proximaC <- createChild(centauri)(Actor.empty[IO, DeathWatchRequest], "proximaC")

      _ <- watch(sol, earth)
      _ <- watch(earth, centauri)
      _ <- watch(earth, proximaC)

      _ <- watch(centauri, proximaB)
      _ <- watch(centauri, proximaC)
      _ <- watch(proximaC, earth) // They're watching

      _ <- List(sol, centauri, earth, proximaB, proximaC).waitForIdle

      solWatchContext <- getWatchContext(sol)
      earthWatchContext <- getWatchContext(earth)

      centauriWatchContext <- getWatchContext(centauri)
      proximaBWatchContext <- getWatchContext(proximaB)
      proximaCWatchContext <- getWatchContext(proximaC)
    } yield (
      solWatchContext,
      earthWatchContext,
      centauriWatchContext,
      proximaBWatchContext,
      proximaCWatchContext
    )).unsafeToFuture().map {
      case (
            solWatchContext,
            earthWatchContext,
            centauriWatchContext,
            proximaBWatchContext,
            proximaCWatchContext
          ) =>
        solWatchContext.watching should have size 1
        (solWatchContext.watching should contain).key(earthWatchContext.actor)
        solWatchContext.watchedBy shouldBe empty

        earthWatchContext.watching should have size 2
        (earthWatchContext.watching should contain).key(centauriWatchContext.actor)
        (earthWatchContext.watching should contain).key(proximaCWatchContext.actor)
        earthWatchContext.watchedBy should have size 2
        earthWatchContext.watchedBy should contain(solWatchContext.actor)
        earthWatchContext.watchedBy should contain(proximaCWatchContext.actor)

        centauriWatchContext.watching should have size 2
        (centauriWatchContext.watching should contain).key(proximaBWatchContext.actor)
        (centauriWatchContext.watching should contain).key(proximaCWatchContext.actor)
        centauriWatchContext.watchedBy should have size 1
        centauriWatchContext.watchedBy should contain(earthWatchContext.actor)

        proximaBWatchContext.watching shouldBe empty
        proximaBWatchContext.watchedBy should have size 1
        proximaBWatchContext.watchedBy should contain(centauriWatchContext.actor)

        proximaCWatchContext.watching should have size 1
        (proximaCWatchContext.watching should contain).key(earthWatchContext.actor)
        proximaCWatchContext.watchedBy should have size 2
        proximaCWatchContext.watchedBy should contain(earthWatchContext.actor)
        proximaCWatchContext.watchedBy should contain(centauriWatchContext.actor)
    }
  }

  it should "fail when watching same actor multiple times" in {
    recoverToExceptionIf[IllegalStateException] {
      (for {
        actorSystem <- ActorSystem[IO]("Death Watch 6", (_: Any) => IO.unit).allocated.map(_._1)
        watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")
        watchee <- createChild(watcher)(Actor.empty[IO, DeathWatchRequest], "watchee")

        _ <- watch(watcher, watchee, "a")
        _ <- watch(watcher, watchee, "b")
        _ <- List(watcher, watchee).waitForIdle
      } yield ()).unsafeToFuture()
    }.map(e =>
      e.getMessage should endWith(
        "[System: Death Watch 6] [Path: kukku://death-watch-6@localhost/user/watcher/watchee] [name: watchee]}) termination message was not overwritten from [Some(Terminated([System: Death Watch 6] [Path: kukku://death-watch-6@localhost/user/watcher/watchee] [name: watchee]},a))] to [Terminated([System: Death Watch 6] [Path: kukku://death-watch-6@localhost/user/watcher/watchee] [name: watchee]},b)]. If this was intended, unwatch first before using `watch` / `watchWith` with another message."
      )
    )
  }

  it should "ignore self watch" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")

      _ <- watch(watcher, watcher)
      _ <- List(watcher).waitForIdle
      watcherWatchContext <- getWatchContext(watcher)
    } yield watcherWatchContext).unsafeToFuture().map { case watcherWatchContext =>
      watcherWatchContext.watching shouldBe empty
      watcherWatchContext.watchedBy shouldBe empty
    }
  }

  it should "be aware of terminated child actor" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 7", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.ignoring[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(watcher)(Actor.ignoring[IO, DeathWatchRequest], "watchee")

      _ <- watch(watcher, watchee)
      _ <- actorSystem.waitForIdle()
      _ <- stop(watchee)
      _ <- actorSystem.waitForIdle()

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)
    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching shouldBe empty
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy shouldBe empty
    }
  }

  it should "stop monitoring watched actor in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 8", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(watcher)(Actor.empty[IO, DeathWatchRequest], "watchee")

      waitAll = List(watcher, watchee).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- waitAll
      _ <- unwatch(watcher, watchee)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)
    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching shouldBe empty
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy shouldBe empty
    }
  }

  it should "stop monitoring all watched actors in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 9", (_: Any) => IO.unit).allocated.map(_._1)
      grandParent <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "grandParent")
      parent <- createChild(grandParent)(Actor.empty[IO, DeathWatchRequest], "parent")
      child1 <- createChild(parent)(Actor.empty[IO, DeathWatchRequest], "child1")
      child2 <- createChild(parent)(Actor.empty[IO, DeathWatchRequest], "child2")

      _ <- watch(grandParent, parent)
      _ <- watch(grandParent, child1)
      _ <- watch(parent, child1)
      _ <- watch(parent, child2)

      waitAll = List(grandParent, parent, child1, child2).waitForIdle
      _ <- waitAll

      _ <- unwatch(grandParent, parent)
      _ <- unwatch(grandParent, child1)
      _ <- unwatch(parent, child1)
      _ <- unwatch(parent, child2)
      _ <- waitAll

      grandParentWatchContext <- getWatchContext(grandParent)
      parentWatchContext <- getWatchContext(parent)
      child1WatchContext <- getWatchContext(child1)
      child2WatchContext <- getWatchContext(child2)
    } yield (grandParentWatchContext, parentWatchContext, child1WatchContext, child2WatchContext))
      .unsafeToFuture()
      .map {
        case (
              grandParentWatchContext,
              parentWatchContext,
              child1WatchContext,
              child2WatchContext
            ) =>
          grandParentWatchContext.watching shouldBe empty
          grandParentWatchContext.watchedBy shouldBe empty

          parentWatchContext.watching shouldBe empty
          parentWatchContext.watchedBy shouldBe empty

          child1WatchContext.watching shouldBe empty
          child1WatchContext.watchedBy shouldBe empty

          child2WatchContext.watching shouldBe empty
          child2WatchContext.watchedBy shouldBe empty
      }
  }

  it should "stop monitoring some watched actors in same hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 10", (_: Any) => IO.unit).allocated.map(_._1)
      grandParent <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "grandParent")
      parent <- createChild(grandParent)(Actor.empty[IO, DeathWatchRequest], "parent")
      child1 <- createChild(parent)(Actor.empty[IO, DeathWatchRequest], "child1")
      child2 <- createChild(parent)(Actor.empty[IO, DeathWatchRequest], "child2")

      _ <- watch(grandParent, parent)
      _ <- watch(grandParent, child1)
      _ <- watch(parent, child1)
      _ <- watch(parent, child2)

      waitAll = List(grandParent, parent, child1, child2).waitForIdle
      _ <- waitAll

      _ <- unwatch(grandParent, parent)
      _ <- unwatch(parent, child2)
      _ <- waitAll

      grandParentWatchContext <- getWatchContext(grandParent)
      parentWatchContext <- getWatchContext(parent)
      child1WatchContext <- getWatchContext(child1)
      child2WatchContext <- getWatchContext(child2)
    } yield (grandParentWatchContext, parentWatchContext, child1WatchContext, child2WatchContext))
      .unsafeToFuture()
      .map {
        case (
              grandParentWatchContext,
              parentWatchContext,
              child1WatchContext,
              child2WatchContext
            ) =>
          grandParentWatchContext.watching should have size 1
          (grandParentWatchContext.watching should contain).key(child1WatchContext.actor)
          grandParentWatchContext.watchedBy shouldBe empty

          parentWatchContext.watching should have size 1
          (parentWatchContext.watching should contain).key(child1WatchContext.actor)
          parentWatchContext.watchedBy shouldBe empty

          child1WatchContext.watching shouldBe empty
          child1WatchContext.watchedBy should have size 2
          child1WatchContext.watchedBy should contain(grandParentWatchContext.actor)

          child2WatchContext.watching shouldBe empty
          child2WatchContext.watchedBy shouldBe empty
      }
  }

  it should "stop monitoring watched actor in different hierarchy" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 11", (_: Any) => IO.unit).allocated.map(_._1)
      sol <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "sol")
      centauri <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "alphaCentauri")

      _ <- watch(sol, centauri)

      waitAll = List(sol, centauri).waitForIdle
      _ <- waitAll

      _ <- unwatch(sol, centauri)
      _ <- waitAll

      solWatchContext <- getWatchContext(sol)
      centauriWatchContext <- getWatchContext(centauri)
    } yield (solWatchContext, centauriWatchContext)).unsafeToFuture().map {
      case (solWatchContext, centauriWatchContext) =>
        solWatchContext.watching shouldBe empty
        solWatchContext.watchedBy shouldBe empty

        centauriWatchContext.watching shouldBe empty
        centauriWatchContext.watchedBy shouldBe empty
    }
  }

  it should "stop monitoring watched actor in different hierarchies" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 12", (_: Any) => IO.unit).allocated.map(_._1)
      sol <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "sol")
      earth <- createChild(sol)(Actor.empty[IO, DeathWatchRequest], "earth")

      centauri <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "alphaCentauri")
      proximaB <- createChild(centauri)(Actor.empty[IO, DeathWatchRequest], "proximaB")
      proximaC <- createChild(centauri)(Actor.empty[IO, DeathWatchRequest], "proximaC")

      _ <- watch(sol, earth)
      _ <- watch(earth, centauri)
      _ <- watch(earth, proximaC)

      _ <- watch(centauri, proximaB)
      _ <- watch(centauri, proximaC)
      _ <- watch(proximaC, earth)

      waitAll = List(sol, centauri, earth, proximaB, proximaC).waitForIdle
      _ <- waitAll

      _ <- unwatch(sol, earth)
      _ <- unwatch(earth, centauri)
      _ <- unwatch(earth, proximaC)

      _ <- unwatch(centauri, proximaB)
      _ <- unwatch(centauri, proximaC)
      _ <- unwatch(proximaC, earth)
      _ <- waitAll

      solWatchContext <- getWatchContext(sol)
      earthWatchContext <- getWatchContext(earth)

      centauriWatchContext <- getWatchContext(centauri)
      proximaBWatchContext <- getWatchContext(proximaB)
      proximaCWatchContext <- getWatchContext(proximaC)
    } yield (
      solWatchContext,
      earthWatchContext,
      centauriWatchContext,
      proximaBWatchContext,
      proximaCWatchContext
    )).unsafeToFuture().map {
      case (
            solWatchContext,
            earthWatchContext,
            centauriWatchContext,
            proximaBWatchContext,
            proximaCWatchContext
          ) =>
        solWatchContext.watching shouldBe empty
        solWatchContext.watchedBy shouldBe empty

        earthWatchContext.watching shouldBe empty
        earthWatchContext.watchedBy shouldBe empty

        centauriWatchContext.watching shouldBe empty
        centauriWatchContext.watchedBy shouldBe empty

        proximaBWatchContext.watching shouldBe empty
        proximaBWatchContext.watchedBy shouldBe empty

        proximaCWatchContext.watching shouldBe empty
        proximaCWatchContext.watchedBy shouldBe empty
    }
  }

  it should "not fail when unwatching same actor multiple times" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 13", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(watcher)(Actor.empty[IO, DeathWatchRequest], "watchee")

      waitAll = List(watcher, watchee).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- waitAll
      _ <- unwatch(watcher, watchee)
      _ <- unwatch(watcher, watchee)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)
    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching shouldBe empty
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy shouldBe empty
    }
  }

  it should "ignore self unwatch" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 14", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.empty[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(watcher)(Actor.empty[IO, DeathWatchRequest], "watchee")

      waitAll = List(watcher, watchee).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- waitAll
      _ <- unwatch(watcher, watcher)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)
    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching should have size 1
        (watcherWatchContext.watching should contain).key(watcheeWatchContext.actor)
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy should have size 1
        watcheeWatchContext.watchedBy should contain(watcherWatchContext.actor)
    }
  }

  it should "remove non-child actor from 'terminated' queue" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 15", (_: Any) => IO.unit).allocated.map(_._1)
      watcher <- actorSystem.actorOf(Actor.ignoring[IO, DeathWatchRequest], "watcher")
      watchee <- actorSystem.actorOf(Actor.ignoring[IO, DeathWatchRequest], "watchee")
      userGuardian <- actorSystem.guardian.get
      waitAll = List(watcher, watchee, userGuardian).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- waitAll
      _ <- stop(watchee)
      _ <- waitAll
      _ <- unwatch(watcher, watchee)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)

    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching shouldBe empty
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy shouldBe empty
    }
  }

  it should "remove watcher reference in watchee.watchedBy when watcher dies" in {
    (for {
      actorSystem <- ActorSystem[IO]("Death Watch 16", (_: Any) => IO.unit).allocated.map(_._1)
      parent <- actorSystem.actorOf(Actor.ignoring[IO, DeathWatchRequest], "parent")
      watcher <- createChild(parent)(Actor.ignoring[IO, DeathWatchRequest], "watcher")
      watchee <- createChild(parent)(Actor.ignoring[IO, DeathWatchRequest], "watchee")
      userGuardian <- actorSystem.guardian.get

      waitAll = List(watcher, watchee, userGuardian).waitForIdle
      _ <- watch(watcher, watchee)
      _ <- stop(watcher)
      _ <- waitAll

      watcherWatchContext <- getWatchContext(watcher)
      watcheeWatchContext <- getWatchContext(watchee)

    } yield (watcherWatchContext, watcheeWatchContext)).unsafeToFuture().map {
      case (watcherWatchContext, watcheeWatchContext) =>
        watcherWatchContext.watching shouldBe empty
        watcherWatchContext.watchedBy shouldBe empty

        watcheeWatchContext.watching shouldBe empty
        watcheeWatchContext.watchedBy shouldBe empty

    }
  }

  private def createChild(
      actorRef: ActorRef[IO, DeathWatchRequest]
  )(
      actor: Actor[IO, DeathWatchRequest],
      childName: String
  ): IO[ActorRef[IO, DeathWatchRequest]] =
    actorRef.cell.flatMap(_.actorOf(actor, childName))

  private def watch(
      watcher: ActorRef[IO, DeathWatchRequest],
      watchee: NoSendActorRef[IO],
      correlationId: String = UUID.randomUUID().toString
  ): IO[NoSendActorRef[IO]] =
    watcher.cell.flatMap(x =>
      x.asInstanceOf[ActorCell[IO, DeathWatchRequest, Any]]
        .watch(watchee, Terminated(watchee, correlationId))
    )

  private def unwatch(
      watcher: ActorRef[IO, DeathWatchRequest],
      watchee: NoSendActorRef[IO]
  ): IO[NoSendActorRef[IO]] =
    watcher.cell.flatMap(_.unwatch(watchee))

  private def stop(actor: NoSendActorRef[IO]): IO[Unit] =
    actor.cell.flatMap(_.stop)

  private def getWatchContext(actor: NoSendActorRef[IO]): IO[WatchContext] =
    for {
      cell <- actor.cell
      watchedBy <- cell.deathWatchContext.watchedByRef.get
      watching <- cell.deathWatchContext.watchingRef.get
    } yield WatchContext(actor, watching = watching, watchedBy = watchedBy)

}
