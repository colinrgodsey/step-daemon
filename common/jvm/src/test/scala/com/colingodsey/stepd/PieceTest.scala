/*
 * Copyright 2020 Colin Godsey
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

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

import javax.imageio.ImageIO
import utest._
import planner.Pieces._

import scala.util.control.NonFatal

object PieceTest extends TestSuite {
  def draw(shape: Trapezoid, name: String): Unit = {
    val nx = 800
    val dt = shape.dt
    val dx = dt / nx

    val img = new BufferedImage(nx, nx, BufferedImage.TYPE_INT_RGB)

    val eaShape = ElasticAdvance(shape, 0.1, 1.0)

    def eaint0vals = for (i <- 0 until nx) yield i -> eaShape(i * dx)
    def eaint1vals = for (i <- 0 until nx) yield i -> eaShape.int1At(i * dx, 0)

    def int0vals = for (i <- 0 until nx) yield i -> shape(i * dx)
    def int1vals = for (i <- 0 until nx) yield i -> shape.int1At(i * dx, 0)
    def int2vals = for (i <- 0 until nx) yield i -> shape.int2At(i * dx, 0, 0)

    def drawPoints(vals: Seq[(Int, Double)], color: Int) = {
      val min = 0 //vals.map(_._2).min
      val max = vals.map(_._2).max

      for {
        (x, y0) <- vals
        y1 = (y0 - min) / (max - min)
        y2 = (1.0 - y1) * 0.95 + 0.025
        y = math.round(y2 * nx).toInt
        if y > 0 && y < nx
      } try {
        img.setRGB(x.toInt, y, color)
      } catch {
        case NonFatal(e) =>
          println((min, max))
          println(max - min)
          println(y)
          throw e
      }
    }

    drawPoints(eaint0vals, Color.MAGENTA.getRGB)
    drawPoints(eaint1vals, Color.CYAN.getRGB)
    drawPoints(int0vals, Color.GREEN.getRGB)
    drawPoints(int1vals, Color.RED.getRGB)
    drawPoints(int2vals, Color.YELLOW.getRGB)

    ImageIO.write(img, "PNG", new File(name))
  }

  val jerk = 5 //10

  val accel = 2.5
  val deccel = -accel

  val dvStart = 10 //5
  val dvEnd = -10

  val startV = 0 //5
  val dist = 50

  /*val accel = 2441.86
  val deccel = -accel

  val dvStart = 22.4 - 0
  val dvEnd = 21.938 - 22.4

  val startV = 0
  val dist = 1.5*/

  val tests = Tests {
    test("vtrap test") {
      val shape = Trapezoid(
        Pulse(accel, dvStart),
        Pulse(deccel, dvEnd),
        dist,
        startV
      )

      draw(shape, "vtrap-test.png")
    }

    test("atrap test") {
      val head = Trapezoid(
        Pulse(jerk, accel),
        Pulse(-jerk, -accel),
        dvStart
      )

      val tail = Trapezoid(
        Pulse(-jerk, deccel),
        Pulse(jerk, -deccel),
        dvEnd
      )

      val shape = Trapezoid(
        head,
        tail,
        dist,
        startV
      )

      try {
        require(head.head.isValid, "head head not valid: " + head.head)
        require(head.middle.isValid, "head middle not valid: " + head.middle)
        require(head.tail.isValid, "head tail not valid: " + head.tail)
      } catch {
        case NonFatal(t) =>
          println(head)
          throw t
      }

      require(shape.middle.isValid, "middle not valid")

      try {
        require(tail.head.isValid, "tail head not valid: " + tail.head)
        require(tail.middle.isValid, "tail middle not valid: " + tail.middle)
        require(tail.tail.isValid, "tail tail not valid: " + tail.tail)
      } catch {
        case NonFatal(t) =>
          println(tail)
          throw t
      }

      require(shape.isValid)

      draw(shape, "atrap-test.png")
    }
  }
}
