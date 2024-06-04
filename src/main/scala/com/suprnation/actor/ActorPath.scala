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

import com.suprnation.actor.ActorPath.splitNameAndUid
import com.suprnation.actor.engine.ActorCell.undefinedUid

import scala.annotation.tailrec
import scala.collection.immutable

object ActorPath {
  final val emptyActorPath: immutable.Iterable[String] = List("")

  final def splitNameAndUid(name: String): (String, Int) = {
    val i: Int = name.indexOf('#')
    if (i < 0) (name, undefinedUid)
    else (name.substring(0, i), Integer.valueOf(name.substring(i + 1)))
  }
}

sealed trait ActorPath {
  def address: Address

  def name: String

  def parent: ActorPath

  def root: RootActorPath

  def /(child: String): ActorPath

  def child(child: String): ActorPath = /(child)

  def /(child: Iterable[String]): ActorPath =
    child.foldLeft(this)((path, elem) => if (elem.isEmpty) path else path / elem)

  def elements: Iterable[String]

  /** Unique identifier of the actor. Used for distinguishing different incarnations of actors with same path (name element)
    */
  def uid: Int

  override val toString: String = address.toString + name

  def toStringWithAddress(address: Address): String

}

final case class RootActorPath(address: Address, name: String = "/") extends ActorPath {
  require(
    name.length == 1 || name.indexOf('/', 1) == -1,
    ("/ may only exist at the beginning of the root actors name, " +
      "it is a path separator and is not legal in ActorPath names: [%s]").format(name)
  )
  require(
    name.indexOf('#') == -1,
    "# is a fragment separator and is not legal in ActorPath names: [%s]".format(name)
  )

  override def parent: ActorPath = this

  override def root: RootActorPath = this

  override def /(child: String): ActorPath = {
    val (childName, uid) = splitNameAndUid(child)
    new ChildActorPath(this, childName, uid)
  }

  override def elements: Iterable[String] = ActorPath.emptyActorPath

  def uid: Int = undefinedUid

  override val toString: String = address.toString + name

  override def toStringWithAddress(addr: Address): String =
    if (address.host.isDefined) address.toString + name
    else addr.toString + name
}

final class ChildActorPath(val parent: ActorPath, val name: String, val uid: Int)
    extends ActorPath {
  if (name.indexOf('/') != -1)
    throw new IllegalArgumentException(
      "/ is a path separator and is not legal in ActorPath names: [%s]".format(name)
    )
  if (name.indexOf('#') != -1)
    throw new IllegalArgumentException(
      "# is a fragment separator and is not legal in ActorPath names: [%s]".format(name)
    )

  private def addressStringLengthDiff(address: Address): Int = {
    val r = root
    if (r.address.host.isDefined) 0
    else address.toString.length - r.address.toString.length
  }

  /** Optimized toString construction. Used by `toString`, `toSerializationFormat`, and friends `WithAddress`
    *
    * @param sb
    *   builder that will be modified (and same instance is returned)
    * @param length
    *   pre-calculated length of the to be constructed String, not necessarily same as sb.capacity because more things may be appended to the sb afterwards
    * @param diff
    *   difference in offset for each child element, due to different address
    * @param rootString
    *   function to construct the root element string
    */
  private def buildToString(
      sb: StringBuilder,
      length: Int,
      diff: Int,
      rootString: RootActorPath => String
  ): StringBuilder = {
    @tailrec
    def rec(p: ActorPath): StringBuilder = p match {
      case r: RootActorPath =>
        val rootStr = rootString(r)
        sb.replace(0, rootStr.length, rootStr)
      case c: ChildActorPath =>
        val start = c.toStringOffset + diff
        val end = start + c.name.length
        sb.replace(start, end, c.name)
        if (c ne this)
          sb.replace(end, end + 1, "/")
        rec(c.parent)
    }

    sb.setLength(length)
    rec(this)
  }

  private def toStringLength: Int = toStringOffset + name.length

  private val toStringOffset: Int = parent match {
    case r: RootActorPath  => r.address.toString.length + r.name.length
    case c: ChildActorPath => c.toStringLength + 1
  }

  override def toStringWithAddress(addr: Address): String = {
    val diff = addressStringLengthDiff(addr)
    val length = toStringLength + diff
    buildToString(new StringBuilder(length), length, diff, _.toStringWithAddress(addr)).toString

  }

  override def address: Address = root.address

  override def /(child: String): ActorPath = {
    val (childName, uid) = splitNameAndUid(child)
    new ChildActorPath(this, childName, uid)
  }

  override def elements: Iterable[String] = {
    @tailrec
    def rec(p: ActorPath, acc: List[String]): immutable.Iterable[String] = p match {
      case _: RootActorPath => acc
      case _                => rec(p.parent, p.name :: acc)
    }

    rec(this, Nil)
  }

  override def root: RootActorPath = {
    @tailrec
    def rec(p: ActorPath): RootActorPath = p match {
      case r: RootActorPath => r
      case _                => rec(p.parent)
    }

    rec(this)
  }

  override val toString: String = {
    val length: Int = toStringLength
    buildToString(new StringBuilder(length), length, 0, _.toString).toString
  }
}
