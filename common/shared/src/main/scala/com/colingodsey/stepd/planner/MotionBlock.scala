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

import com.colingodsey.stepd.Math._

sealed trait MotionBlock {
  def time: Double
  def move: MoveDelta

  def getPos(t: Double): Double
  def getVel(dt: Double): Double

  def posIterator(tickRate: Double, eAdvanceK: Double): Iterator[Vec4] = new Iterator[Vec4] {
    val ticks = (time * tickRate).toInt
    val div = time / ticks

    var tick = 0

    def hasNext: Boolean = tick < ticks

    def next(): Vec4 = {
      val dt = tick * div

      val x = if(tick == 0) move.from
      else move.from + move.d.normal * getPos(dt)

      val eFac = getVel(dt) * move.d.normal.e * eAdvanceK
      val eOffset = move.isPrintMove match {
        case true => Vec4.E * eFac
        case false => Vec4.Zero
      }

      tick += 1

      x + eOffset
    }
  }
}

/**
 * Let's have fun with... piecewise integration?
 *
 * Contains various pieces that we can assemble together and integrate.
 * Each piece is definitive with a defined time domain.
 *
 * Pieces are assembled in a time-independent way, where the first integral
 * is provided instead of the time domain. This allows the shapes to be defined
 * by using known slopes (such as jerk, acceleration, velocity) and time is
 * derived from that.
 */
object Pieces {

  trait Piece {
    def dt: Double
    def isValid: Boolean

    def der1At(dt: Double): Double

    def apply(dt: Double): Double

    def int1At(dt: Double, c0: Double): Double
    def int2At(dt: Double, c0: Double, c1: Double): Double
    def int3At(dt: Double, c0: Double, c1: Double, c2: Double): Double

    def apply(): Double = apply(dt)

    def int1(c0: Double): Double = int1At(dt, c0)
    def int2(c0: Double, c1: Double): Double = int2At(dt, c0, c1)
    def int3(c0: Double, c1: Double, c2: Double): Double = int3At(dt, c0, c1, c2)
  }

  /**
   * N+1 order pulse, N order line.
   *
   * @param dy - N order slope
   * @param area - N order first integral - c
   */
  case class Pulse(dy: Double, area: Double) extends Piece {
    val dt = if (dy == 0.0) 0.0 else area / dy

    def isValid = dt >= 0.0

    def der1At(dt: Double): Double = 0.0

    def apply(dt: Double): Double = dy

    def int1At(dt: Double, c0: Double) =
      c0 + dy * dt
    def int2At(dt: Double, c0: Double, c1: Double) =
      c0 + c1 * dt + dy * dt * dt / 2.0
    def int3At(dt: Double, c0: Double, c1: Double, c2: Double) =
      c0 + c1 * dt + c2 * dt * dt / 2.0 + dy * dt * dt * dt / 6.0
  }

  /**
   * N+2 order head and tail piece, with an N+1 order gap pulse for the middle.
   *
   * @param head N+2 order pulse for head slope
   * @param tail N+2 order pulse for tail slope
   * @param area N order first integral - c
   * @param c N order constant
   */
  case class Trapezoid(head: Piece, tail: Piece, area: Double, c: Double = 0) extends Piece {
    // one order lower than head and tail
    val headArea = head.int2(0, c)
    val tailArea = tail.int2(0, head.int1(c))
    val middle = Pulse(head.int1(c), area - headArea - tailArea)

    val dtTailStart = head.dt + middle.dt
    val dt = dtTailStart + tail.dt

    def isValid = head.isValid && middle.isValid && tail.isValid

    def der1At(dt: Double): Double =
      if (dt > dtTailStart) tail.apply(dt - dtTailStart)
      else if (dt > head.dt) 0.0
      else head.apply(dt)

    //each piece needs to reference the last piece for its cX values
    def apply(dt: Double): Double =
      if (dt > dtTailStart) tail.int1At(dt - dtTailStart, apply(dtTailStart))
      else if (dt > head.dt) apply(head.dt)
      else head.int1At(dt, c)

    def int1At(dt: Double, c0: Double): Double =
      if (dt > dtTailStart) tail.int2At(dt - dtTailStart, int1At(dtTailStart, c0), apply(dtTailStart))
      else if (dt > head.dt) middle.int1At(dt - head.dt, int1At(head.dt, c0))
      else head.int2At(dt, c0, c)

    def int2At(dt: Double, c0: Double, c1: Double): Double =
      if (dt > dtTailStart) tail.int3At(dt - dtTailStart, int2At(dtTailStart, c0, c1), int1At(dtTailStart, c1), apply(dtTailStart))
      else if (dt > head.dt) middle.int2At(dt - head.dt, int2At(head.dt, c0, c1), int1At(head.dt, c1))
      else head.int3At(dt, c0, c1, c)

    def int3At(dt: Double, c0: Double, c1: Double, c2: Double) = Double.NaN //???
  }

  case class ElasticAdvance(piece: Piece, kFactor: Double, pieceFactor: Double) extends Piece {
    def dt: Double = piece.dt
    def isValid: Boolean = piece.isValid

    def der1At(dt: Double): Double = Double.NaN

    def apply(dt: Double): Double = piece.apply(dt) + piece.der1At(dt) * kFactor * pieceFactor

    def int1At(dt: Double, c0: Double): Double = piece.int1At(dt, c0) + piece(dt) * kFactor * pieceFactor

    def int2At(dt: Double, c0: Double, c1: Double): Double = Double.NaN

    def int3At(dt: Double, c0: Double, c1: Double, c2: Double): Double = Double.NaN
  }
}

final case class ATrapezoid(
  frStart: Double, frStartJerk: Double, frAccel: Double,
  frJerk: Double, move: MoveDelta,
  frDeccel: Double, frEndJerk: Double, frEnd: Double)
    extends MotionBlock {
  import Pieces._

  val startArea = move.f - frStart
  val endArea = frEnd - move.f

  val shape = Trapezoid(
    Trapezoid(
      Pulse(frStartJerk, frAccel),
      Pulse(-frJerk, -frAccel),
      startArea
    ),
    Trapezoid(
      Pulse(-frJerk, frDeccel),
      Pulse(frEndJerk, -frDeccel),
      endArea
    ),
    move.length,
    frStart
  )

  def time = shape.dt

  if (!shape.isValid) {
    if (frStart > frEnd) throw PreEaseLimit
    else throw PostEaseLimit
  }

  def getPos(dt: Double): Double =
    clamp(0.0, shape.int1At(dt, 0), move.length)

  def getVel(dt: Double): Double = shape(dt)
}

final case class VTrapezoid(frStart: Double, frAccel: Double, move: MoveDelta, frDeccel: Double, frEnd: Double)
    extends MotionBlock {
  import Pieces._

  val startArea = move.f - frStart
  val endArea = frEnd - move.f

  val shape = Trapezoid(
    Pulse(frAccel, startArea),
    Pulse(frDeccel, endArea),
    move.length,
    frStart
  )

  def time = shape.dt

  if (!shape.isValid) {
    if (frStart > frEnd) throw PreEaseLimit
    else throw PostEaseLimit
  }

  def getPos(dt: Double): Double =
    clamp(0.0, shape.int1At(dt, 0), move.length)

  def getVel(dt: Double): Double = shape(dt)
}

final case class VTrapezoidOld(frStart: Double, frAccel: Double, move: MoveDelta, frDeccel: Double, frEnd: Double)
    extends MotionBlock {
  val accelDf = move.f - frStart
  val accelTime = if (frAccel == 0) 0.0 else accelDf / frAccel
  val accelDist = frAccel * accelTime * accelTime * 0.5 + frStart * accelTime

  val deccelDf = frEnd - move.f
  val deccelTime = if (frDeccel == 0) 0.0 else deccelDf / frDeccel
  val deccelDist = frDeccel * deccelTime * deccelTime * 0.5 + move.f * deccelTime

  val coastDist = move.length - deccelDist - accelDist
  val coastTime = coastDist / move.f

  val deccelPos = move.length - deccelDist
  val deccelStartTime = accelTime + coastTime

  val time = accelTime + coastTime + deccelTime

  if ((accelDist + deccelDist) > move.length) {
    if (accelDist > deccelDist) throw PreEaseLimit
    else throw PostEaseLimit
  }

  require(accelTime >= 0, (accelTime, this).toString)
  require(deccelTime >= 0, (deccelTime, this).toString)
  require(accelDist >= 0, (accelDist, this).toString)
  require(deccelDist >= 0, (deccelDist, this).toString)

  def getPos(t: Double): Double = {
    val ret = if (t < accelTime) {
      val dt = t
      /* zero + */      frStart * dt +    frAccel * dt * dt * 0.5
    } else if (t >= deccelStartTime) {
      val dt = t - deccelStartTime
      deccelPos +       move.f * dt +     frDeccel * dt * dt * 0.5
    } else {
      val dt = t - accelTime
      accelDist +       move.f * dt /* +  zero */
    }

    clamp(0.0, ret, move.length)
  }

  def getVel(dt: Double): Double = 0.0
}
