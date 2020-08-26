/*
 * Copyright 2017 Colin Godsey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.colingodsey.stepd.planner

import com.colingodsey.stepd.GCode._
import com.colingodsey.stepd.Math.Vec4

object DeltaProcessor {
  //absolute values
  case class Move(x: Double, y: Double, z: Double, e: Double, f: Double) extends Vec4 //feedrate per second
}

//takes absolute positions and produces move deltas
trait DeltaProcessor {
  import DeltaProcessor._

  var pos = Vec4.Zero
  private var _fr = 0.0

  def process(delta: MoveDelta): Unit
  def frScale: Double
  def isAbsolute: Boolean

  def fr = _fr

  def process(move: Move): Unit = {
    val d = MoveDelta(pos, move, move.f)

    pos = move

    //warm normal lazy val
    d.d.normal
    d.d.abs.normal

    process(d)
  }

  def mRel(opt: Option[Double], dPos: Double): Double = opt match {
    case None => dPos
    case Some(x) if isAbsolute => x
    case Some(x) => x + dPos
  }

  def process(move: GMove): Unit = {
    val x = mRel(move.x, pos.x)
    val y = mRel(move.y, pos.y)
    val z = mRel(move.z, pos.z)
    val e = mRel(move.e, pos.e)
    val f = move.f.getOrElse(fr)

    _fr = f

    if(move.isFrOnly) None
    else process(Move(x, y, z, e, f / 60.0f * frScale))
  }

  def process(setPos: SetPos): Unit = {
    pos = Vec4(
      setPos.x.getOrElse(pos.x),
      setPos.y.getOrElse(pos.y),
      setPos.z.getOrElse(pos.z),
      setPos.e.getOrElse(pos.e)
    )
  }
}