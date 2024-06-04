package com.suprnation.samples.logic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

trait SimConfig {
  val inverterDelay = 1 milliseconds
  val andDelay = 1 milliseconds
  val orDelay = 10 milliseconds
}
