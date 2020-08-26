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

import utest._
import com.colingodsey.stepd.Math._

object DummyTest extends TestSuite {
  import DeltaProcessor._

  object Test100Square {
    val moves = Seq(
      Move(0, 100, 0, 10, 80),
      Move(100, 100, 0, 0, 60),
      Move(100, 0, 0, 10, 30),
      Move(0, 0, 0, 0, 40),
      Move(100, 100, 0, 0, 50),
      Move(0, 0, 0, 0, 30),
      Move(50, 100, 0, 0, 80)
    )
  }
  class Test100Square extends TestSquare(Test100Square.moves)

  object Test100RandomSquare {
    def moves(n: Int) = for(_ <- 0 until n) yield {
      val fr = math.random * 50 + 5

      Move(math.random.toFloat * 100.0f,
        math.random.toFloat * 100.0f,
        math.random.toFloat * 100.0f,
        math.random.toFloat * 100.0f,
        fr.toFloat)
    }
  }
  class Test100RandomSquare(nMoves: Int = 100, tick: Double = 0.0008f) extends TestSquare(Test100RandomSquare.moves(nMoves), tick)

  class TestSquare(moves: Seq[Move], tick: Double = 0.01f) {
    var resizes = 0

    val phase = new PhysicsProcessor {
      //val acc: Accel = Position.One * 0.01f
      val acc = Vec4(2000, 1500, 100, 10000)
      val jerk = Vec4(15, 10, 0.4f, 5)
      val sJerk = Vec4.One * 1e10

      var trap: MotionBlock = null

      def process(trap: MotionBlock): Unit = this.trap = trap

      def recordLookaheadHalt(): Unit = {}

      def recordFault(fault: MathFault): Unit = resizes += 1
    }

    val deltas = {
      var lastPos: Vec4 = Vec4.Zero

      moves map { move =>
        val d = MoveDelta(lastPos, move, move.f)

        lastPos = move

        d
      }
    }

    def run(pointFunc: (Vec4, Double) => Unit): Unit = {
      var t = 0.0

      for(i <- 0 until deltas.length) {
        val delta = deltas(i)
        val preDelta = if(i == 0) delta else deltas(i - 1)
        val postDelta = if(i == (deltas.length - 1)) delta else deltas(i + 1)

        phase.createTrapezoidSafe(preDelta, delta, postDelta, maxTimes = 5)

        val trap = phase.trap
        var dt = 0.0

        trap.posIterator(tick, 0) foreach { pos =>
          pointFunc(pos, dt + t)

          dt += tick
        }

        t += trap.time
      }
    }
  }

  val tests = this {
    'Traptest {
      val delta = MoveDelta(
        Vec4(0, 0, 0, 0),
        Vec4(10, 0, 0, 0),
        1
      )

      val trap = VTrapezoid(0.5f, 0.1f, delta, -0.1f, 0.5f)
      //val trap = Trapezoid(0f, 0.1f, delta, -0.1f, 0f)

      //println(trap.accelTime, trap.coastTime, trap.deccelTime, trap.time)

      val tick = 0.001f

      var dt = 0.0f

      while(dt < trap.time) {
        val d = trap.getPos(dt)

        val pos = trap.move.from + trap.move.d.normal * d

        //println(s"${dt}, ${pos.x}, ${pos.y}, ${pos.z}, ${pos.e}")

        dt += tick
      }
    }

    'printTest {
      val test = new Test100Square

      test.run { (pos, t) =>
        //println(s"$t, ${pos.x}, ${pos.y}, ${pos.z}, ${pos.e}")
      }
    }

    'printRandTest {
      val test = new Test100RandomSquare(10, 0.01f)

      test.run { (pos, t) =>
        println(s"$t, ${pos.x}, ${pos.y}, ${pos.z}, ${pos.e}")
      }
    }

    'boundsTest {
      val test = new Test100Square
      var lastT = 0.0
      var i = 0

      test.run { (pos, t) =>
        require(pos.x < 100.0001 && pos.x > -0.0001, "x bounds failed " + pos.x)
        require(pos.y < 100.0001 && pos.y > -0.0001, "y bounds failed " + pos.y)

        lastT = t
        i += 1
      }

      require(test.resizes == 0)

      println(s"t: $lastT i: $i resizes: ${test.resizes}")
    }

    'boundsTest2 {
      val test = new Test100RandomSquare(100)
      var lastT = 0.0
      var i = 0

      test.run { (pos, t) =>
        require(pos.x < 100.0001 && pos.x > -0.0001, "x bounds failed " + pos.x)
        require(pos.y < 100.0001 && pos.y > -0.0001, "y bounds failed " + pos.y)

        lastT = t
        i += 1
      }

      println(s"t: $lastT i: $i resizes: ${test.resizes}")
    }
  }
}
