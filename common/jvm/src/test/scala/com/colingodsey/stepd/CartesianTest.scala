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

import com.colingodsey.stepd.Math.Vec4
import com.colingodsey.stepd.planner.StepProcessor

import utest._

object CartesianTest extends TestSuite {
  val frogGCode = {
    val stream = getClass.getResourceAsStream("/hellfrog.gcode")
    scala.io.Source.fromInputStream(stream).getLines.mkString("\r\n")
  }

  class FrogIterator extends CartesianChunkIterator(frogGCode, StepProcessor.PageFormat.SP_4x4D_128) {
    val acc = Vec4(2000, 1500, 100, 10000)
    val jerk = Vec4(15, 10, 0.4f, 5)
    val sJerk = Vec4.One * 1e10
    val stepsPerMM = Vec4(80, 80, 1600, 95.2)
    val ticksPerSecond: Int = 30000

    var isAbsolute = true
    var frScale = 1.0

    def sendOk(n: Option[Int]): Unit = {}
  }

  val tests = this {
    /*"Raw iterator" - {
      val itr0 = new FrogIterator
      val itr = CartesianDeltaIterator(itr0)

      var pos = Vec4.Zero
      var realPos = Vec4.Zero

      def checkAxis(x: Double): Unit = {
        require(x >= 0, "negative axis! " + x)
        require(x <= 200, "too large of axis! " + x)
      }

      itr foreach {
        case Left(delta) =>
          pos += delta
          realPos = Vec4(
            pos.x / itr0.stepsPerMM.x,
            pos.y / itr0.stepsPerMM.y,
            pos.z / itr0.stepsPerMM.z,
            pos.e / itr0.stepsPerMM.e
          )

          checkAxis(realPos.x)
          checkAxis(realPos.y)
          checkAxis(realPos.z)

        case Right(cmd) =>
      }

      println("last pos: " + realPos)
    } */
  }
}
