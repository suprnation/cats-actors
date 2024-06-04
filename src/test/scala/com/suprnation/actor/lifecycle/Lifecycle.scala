package com.suprnation.actor.lifecycle

import cats.effect.IO
import com.suprnation.actor.ActorRef

object Lifecycle {

  case class WatchContext(
      actor: ActorRef[IO],
      watching: Map[ActorRef[IO], Option[Any]],
      watchedBy: Set[ActorRef[IO]]
  )
}
