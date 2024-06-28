package com.suprnation.actor.lifecycle

import cats.effect.IO
import com.suprnation.actor.ActorRef.NoSendActorRef

object Lifecycle {

  case class WatchContext(
      actor: NoSendActorRef[IO],
      watching: Map[NoSendActorRef[IO], Option[Any]],
      watchedBy: Set[NoSendActorRef[IO]]
  )
}
