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

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

import scala.concurrent.blocking

import org.apache.commons.math3.analysis.interpolation.{BicubicInterpolator, PiecewiseBicubicSplineInterpolator}

import com.colingodsey.stepd.Math._

object MeshLeveling {
  object OutputLine {
    def unapply(arg: String): Option[Point] = parseLine(arg)
  }

  case class Point(x: Double, y: Double, offset: Double) {
    def vec = Vec3(x, y, offset)
  }

  class Reader(data: Array[Float], width: Int, height: Int) extends MeshLevelingReader {
    val maxZ = data.max
    val minZ = data.min

    def getOffset(x: Double, y: Double): Float = {
      val xi = x.toInt
      val yi = y.toInt

      val isLastX = xi + 1 >= width
      val isLastY = yi + 1 >= height

      val q11 =
        data(   xi     +       yi * width)

      val q21 = if(isLastX) q11
      else data(xi + 1 +       yi * width)

      val q12 = if(isLastY) q11
      else data(xi     + (yi + 1) * width)

      val q22 = if(isLastX || isLastY) q11
      else data(xi + 1 + (yi + 1) * width)

      val dx1 = (x - xi).toFloat
      val dy1 = (y - yi).toFloat
      val dx2 = 1.0f - dx1
      val dy2 = 1.0f - dy1

      val fy1 = q11 * dx2 + q21 * dx1
      val fy2 = q12 * dx2 + q22 * dx1

      fy1 * dy2 + fy2 * dy1
    }
  }

  def parseLine(line: String): Option[MeshLeveling.Point] = {
    //Bed X: 179.000 Y: 20.000 Z: 0.135
    val lead = "Bed " //leaves: "X: 179.000 Y: 20.000 Z: 0.135"
    val ident = lead + "X:"

    if(!line.startsWith(ident)) return None

    require(line.startsWith(lead))

    val split = line.drop(lead.length).split(' ')

    val x = split(1).toDouble
    val y = split(3).toDouble
    val z = split(5).toDouble

    require(!x.isNaN && !y.isNaN && !z.isNaN)

    Some(MeshLeveling.Point(x, y, z))
  }
}

//TODO: this *REALLY* needs to run a self test, especially with the flaky BicubicInterpolator
case class MeshLeveling(val points: Seq[MeshLeveling.Point], val xMax: Int, val yMax: Int) {
  import MeshLeveling._

  val maxSampleX = points.iterator.map(_.x).max
  val minSampleX = points.iterator.map(_.x).min
  val maxSampleY = points.iterator.map(_.y).max
  val minSampleY = points.iterator.map(_.y).min

  val surfaceNormal = averageNormal

  val sortX = (points.map(_.x) ++ Seq(0.0, xMax)).distinct.sorted.toIndexedSeq
  val sortY = (points.map(_.y) ++ Seq(0.0, yMax)).distinct.sorted.toIndexedSeq

  val pointMap = points.map(point => (point.x, point.y) -> point).toMap

  //TODO: produce std dev
  val avgD = points.map(x => x.vec * surfaceNormal).sum / points.length.toDouble

  val function = {
    val values = Array.fill(sortX.length)(new Array[Double](sortY.length))

    //must be a full mesh! every X point must have every corresponding Y point
    for {
      xi <- 0 until sortX.length
      yi <- 0 until sortY.length
    } {
      val x = sortX(xi)
      val y = sortY(yi)
      val point = getSamplePoint(x, y)
      //val point = createSamplePoint(x, y)

      values(xi)(yi) = point.offset
    }

    //the good interpolator requires min 5 points each
    if(sortX.length >= 5 && sortY.length >= 5) {
      val interpolator = new PiecewiseBicubicSplineInterpolator

      interpolator.interpolate(sortX.toArray, sortY.toArray, values)
    } else {
      val interpolator = new BicubicInterpolator

      //TODO: might explode due to https://issues.apache.org/jira/browse/MATH-1138
      interpolator.interpolate(sortX.toArray, sortY.toArray, values)
    }
  }

  def vec3To2(v: Vec3) = Vec2(v.x, v.y)

  def getSamplePoint(x: Double, y: Double) = pointMap.get(x, y).getOrElse {
    //point missing within mesh
    if(x >= minSampleX && x <= maxSampleX && y >= minSampleY && y <= maxSampleY)
      sys.error(s"missing mesh leveling point ($x, $y)")

    //otherwise create points outside the mesh
    createSamplePoint(x, y)
  }

  //extrapolate points using closest point plane
  def createSamplePoint(x: Double, y: Double): Point = {
    //solve using scalar equation of plane
    val closest = getClosestPoint(x, y)

    //solve for d and n using the closest point
    val n = surfaceNormal
    val d = closest.vec * n

    val z = (d - n.x * x - n.y * y) / n.z

    Point(x, y, z)
  }

  def averageNormal = {
    var accumN = Vec3.Zero
    var totalN = 0.0

    for(a <- points.iterator) {
      val n = calculateNormal(a)

      accumN += n
      totalN += 1.0
    }

    (accumN / totalN).normal
  }

  def calculateNormal(a: Point): Vec3 = {
    val total = for {
      b <- points
      c <- points
      //if all 3 unique
      if Set(a, b, c).size == 3
      norm <- calculateNormal(a.vec, b.vec, c.vec)
    } yield norm

    (total.reduceLeft(_ + _) / total.length.toDouble).normal
  }

  def calculateNormal(a: Vec3, b: Vec3, c: Vec3): Option[Vec3] = {
    val n2d1 = vec3To2(b - a)
    val n2d2 = vec3To2(c - a)
    val n1 = (b - a).normal
    val n2 = (c - a).normal

    //make sure its not colinear. work in 2d for this
    if(math.abs(n2d1.normal * n2d2.normal) >= (1.0 - Epsilon.e)
        || n2d1.length < Epsilon.e
        || n2d2.length < Epsilon.e)
      return None

    val norm0 = (n1 x n2).normal
    val norm = if(norm0.z > 0) norm0 else -norm0
    val dist = a * norm

    //sanity check. all points should be on plane
    require(math.abs(b * norm - dist) < Epsilon.e)
    require(math.abs(c * norm - dist) < Epsilon.e)

    Some(norm)
  }

  def calculateFor(x: Double, y: Double): Double =
    function.value(x, y)

  def getClosestPoint(x: Double, y: Double): Point =
    points.sortBy { point =>
      math.sqrt(point.x * x + point.y * y)
    }.head

  def produce() = {
    val out = new Array[Float](xMax * yMax)

    for(x <- 0 until xMax; y <- 0 until yMax) {
      val f = calculateFor(x, y)
      val idx = x + y * xMax

      out(idx) = f.toFloat
    }

    out
  }

  def reader() = new Reader(produce(), xMax, yMax)

  def createImage(outPath: String, subSamples: Int = 10): Unit = blocking {
    val img = new BufferedImage(xMax * subSamples, yMax * subSamples, BufferedImage.TYPE_INT_RGB)

    val reader = this.reader()

    for {
      x <- 0 until xMax * subSamples
      y <- 0 until yMax * subSamples
    } {
      val z = reader.getOffset(x / subSamples.toDouble, y / subSamples.toDouble).toDouble

      val z0 = (z - reader.minZ) / (reader.maxZ - reader.minZ)
      //val z0 = (z + 1.0) / 2.0
      val totalSpace = 255 * 3
      val z1 = z0 * totalSpace

      require(z0 >= -0.00001f, z0.toString)
      require(z0 <= 1.000001f, z0.toString)

      val r = math.min(math.max(z1, 0), 255).toInt
      val g = math.min(math.max(z1 - 255, 0), 255).toInt
      val b = math.min(math.max(z1 - 255 * 2, 0), 255).toInt
      val c = math.min(math.max(z0 * 255, 0), 255).toInt

      //val color = new Color(r - b, g, b).getRGB
      //val color = Color.getHSBColor(z0.toFloat, 1.0f, r / 255f).getRGB
      val color = Color.getHSBColor(z0.toFloat, 1.0f, 1.0f).getRGB
      //val color = new Color(c, c, c).getRGB

      img.setRGB(x, y, color)
    }

    ImageIO.write(img, "PNG", new File(outPath))
  }
}
