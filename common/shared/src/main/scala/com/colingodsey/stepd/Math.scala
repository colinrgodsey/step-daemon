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

package com.colingodsey.stepd

import scala.util.control.NoStackTrace

object Math { mathSelf =>
  case class Epsilon(e: Double)

  object Epsilon {
    implicit val default = Epsilon(1e-12)

    def e(implicit ep: Epsilon) = ep.e
  }

  @inline final def clamp(min: Double, x: Double, max: Double): Double =
    math.min(math.max(x, min), max)

  sealed trait MathFault extends Throwable with NoStackTrace {
    val simpleName = {
      val str = getClass.getSimpleName

      str.take(str.length - 1)
    }

    override def toString() = simpleName
  }

  object Vec2 {
    @inline def apply(x: Double, y: Double): mathSelf.Vec2 =
      this.Vec2(x, y)

    def apply(seq: Int => Double): mathSelf.Vec2 =
      this.Vec2(seq(0), seq(1))

    private final case class Vec2(x: Double, y: Double) extends mathSelf.Vec2

    val X     = this(1, 0)
    val Y     = this(0, 1)
    val Zero  = this(0, 0)
    val One   = Zero + X + Y
    val Unit  = One.normal
  }

  trait Vec2 { _: Equals =>
    def x: Double
    def y: Double

    lazy val length = math.sqrt(this ⋅ this)

    lazy val invLength = 1.0 / length

    lazy val normal =
      if(length == 0.0) Vec2.Zero
      else if(length == 1.0) this
      else this * invLength

    lazy val abs =
      Vec2(math.abs(x),  math.abs(y))

    @inline def unary_-() = this * -1

    @inline final def +(other: Vec2) =
      Vec2(x + other.x,  y + other.y)

    @inline final def -(other: Vec2) =
      Vec2(x - other.x,  y - other.y)

    @inline final def *(scalar: Double): Vec2 =
      Vec2(x * scalar,   y * scalar)

    /** Dot product */
    @inline final def ⋅(other: Vec2): Double =
      x * other.x + y * other.y
    @inline final def *(other: Vec2): Double = this ⋅ other

    @inline final def /(scalar: Double) = this * (1.0 / scalar)

    override def canEqual(that: Any): Boolean = that match {
      case _: Vec2 => true
      case _ => false
    }

    override def equals(that: Any): Boolean = that match {
      case that: Vec2 =>
        x == that.x && y == that.y
      case _ =>
        false
    }
  }
  
  object Vec3 {
    @inline def apply(x: Double, y: Double, z: Double): mathSelf.Vec3 =
      this.Vec3(x, y, z)

    def apply(seq: Int => Double): mathSelf.Vec3 =
      this.Vec3(seq(0), seq(1), seq(2))

    private final case class Vec3(x: Double, y: Double, z: Double) extends mathSelf.Vec3

    val X     = this(1, 0, 0)
    val Y     = this(0, 1, 0)
    val Z     = this(0, 0, 1)
    val Zero  = this(0, 0, 0)
    val One   = Zero + X + Y + Z
    val Unit  = One.normal
  }

  trait Vec3 { _: Equals =>
    def x: Double
    def y: Double
    def z: Double

    lazy val length = math.sqrt(this ⋅ this)

    lazy val invLength = 1.0 / length

    lazy val normal =
      if(length == 0.0) Vec3.Zero
      else if(length == 1.0) this
      else this * invLength

    lazy val abs =
      Vec3(math.abs(x),  math.abs(y),  math.abs(z))

    @inline def unary_-() = this * -1

    @inline final def +(other: Vec3) =
      Vec3(x + other.x,  y + other.y,  z + other.z)

    @inline final def -(other: Vec3) =
      Vec3(x - other.x,  y - other.y,  z - other.z)

    @inline final def *(scalar: Double): Vec3 =
      Vec3(x * scalar,   y * scalar,   z * scalar)

    /** Dot product */
    @inline final def ⋅(other: Vec3): Double =
      x * other.x + y * other.y + z * other.z
    @inline final def *(other: Vec3): Double = this ⋅ other

    @inline final def /(scalar: Double) = this * (1.0 / scalar)

    @inline def x (other: Vec3): Vec3 = Vec3(
      y * other.z - z * other.y,
      z * other.x - x * other.z,
      x * other.y - y * other.x
    )

    override def canEqual(that: Any): Boolean = that match {
      case _: Vec3 => true
      case _ => false
    }

    override def equals(that: Any): Boolean = that match {
      case that: Vec3 =>
        x == that.x && y == that.y && z == that.z
      case _ =>
        false
    }
  }

  object Vec4 {
    @inline def apply(x: Double, y: Double, z: Double, e: Double): mathSelf.Vec4 =
      this.Vec4(x, y, z, e)

    def apply(seq: Int => Double): mathSelf.Vec4 =
      this.Vec4(seq(0), seq(1), seq(2), seq(3))

    private final case class Vec4(x: Double, y: Double, z: Double, e: Double) extends mathSelf.Vec4

    val X     = this(1, 0, 0, 0)
    val Y     = this(0, 1, 0, 0)
    val Z     = this(0, 0, 1, 0)
    val E     = this(0, 0, 0, 1)
    val Zero  = this(0, 0, 0, 0)
    val One   = Zero + X + Y + Z + E
    val Unit  = One.normal
  }

  trait Vec4 { _: Equals =>
    def x: Double
    def y: Double
    def z: Double
    def e: Double

    lazy val length = math.sqrt(this ⋅ this)

    lazy val invLength = 1.0 / length

    lazy val normal =
      if(length == 0.0) Vec4.Zero
      else if(length == 1.0) this
      else this * invLength

    lazy val abs =
      Vec4(math.abs(x),  math.abs(y),  math.abs(z),  math.abs(e))

    @inline def unary_-() = this * -1

    @inline final def +(other: Vec4) =
      Vec4(x + other.x,  y + other.y,  z + other.z,  e + other.e)

    @inline final def -(other: Vec4) =
      Vec4(x - other.x,  y - other.y,  z - other.z,  e - other.e)

    @inline final def *(scalar: Double): Vec4 =
      Vec4(x * scalar,   y * scalar,   z * scalar,   e * scalar)

    /** Dot product */
    @inline final def ⋅(other: Vec4): Double =
      x * other.x + y * other.y + z * other.z + e * other.e
    @inline final def *(other: Vec4): Double = this ⋅ other

    @inline final def /(scalar: Double) = this * (1.0 / scalar)

    override def canEqual(that: Any): Boolean = that match {
      case _: Vec4 => true
      case _ => false
    }

    override def equals(that: Any): Boolean = that match {
      case that: Vec4 =>
        x == that.x && y == that.y && z == that.z && e == that.e
      case _ =>
        false
    }
  }

  sealed trait EaseLimit extends MathFault

  case object PreEaseLimit extends EaseLimit
  case object PostEaseLimit extends EaseLimit

  case object JerkFault extends MathFault
  case object LookaheadFault extends MathFault

  implicit val stepDEpsilon = Epsilon(1e-6)
}

