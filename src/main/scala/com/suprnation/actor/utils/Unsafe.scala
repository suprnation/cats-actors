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

package com.suprnation.actor.utils

import com.suprnation.actor.{Actor, ActorContext, ActorRef}

import java.lang.reflect.Field
import scala.annotation.tailrec

object Unsafe {

  /** Set the actor context reflectively, we do this to avoid a ref and have a double map on the object Note that the actor is update via the ref and we are guaranteed that it will be only accessed once due to the take.
    */
  def setActorContext[F[+_]](actor: Actor[F], context: ActorContext[F]): Actor[F] =
    getDeclaredField(actor.getClass, "context").fold {
      throw new Error(s"Class: [${actor.getClass}] does not inherit from Actor.  ")
    } { contextField =>
      contextField.setAccessible(true)
      contextField.set(actor, context)
      actor
    }

  /** Set the actor self
    *
    * @param actor
    *   the actor
    * @param self
    *   the self address
    */
  def setActorSelf[F[+_]](actor: Actor[F], self: ActorRef[F]): Actor[F] =
    getDeclaredField(actor.getClass, "self").fold(
      throw new Error(s"Class: [${actor.getClass}] does not inherit from Actor.  ")
    ) { field =>
      field.setAccessible(true)
      field.set(actor, self)
      actor
    }

  @tailrec
  private def getDeclaredField[F[+_]](clazz: Class[?], name: String): Option[Field] =
    clazz.getSuperclass.getDeclaredFields.toList.find(field => field.getName == name) match {
      case None =>
        // check the super class
        if (clazz != classOf[Actor[F]] && clazz != classOf[AnyRef]) {
          // We are not at the most topmost field let's move up!
          getDeclaredField(clazz.getSuperclass, name)
        } else {
          None
        }

      case Some(field) => Some(field)
    }
}
