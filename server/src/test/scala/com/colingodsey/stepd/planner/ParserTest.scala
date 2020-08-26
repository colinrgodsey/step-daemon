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
import com.colingodsey.stepd.GCode._
import com.colingodsey.stepd.{GCodeParser, LineParser}

import scala.io.Source

object ParserTest extends TestSuite {
  val tests = this {
    'Test {
      val stream = getClass.getResourceAsStream("/g_test1.gcode")
      val testGCode = scala.io.Source.fromInputStream(stream).getLines.mkString("\r\n")

      var outCmds = Array.newBuilder[Command]

      val expect = Set[Command](
        GMove(Some(77.835f), Some(105.927f), None, Some(32.12709f), None)(null),
        GMove(Some(81.637f), Some(102.126f), None, Some(32.20156f), None)(null),
        GMove(Some(81.504f), Some(102.036f), None, Some(32.20378f), None)(null)
      )

      object testParser extends LineParser with GCodeParser {
        def processCommand(cmd: Command): Unit = {
          //println(cmd)
          outCmds += cmd
        }

        def sendOk(n: Option[Int]): Unit = {}
      }

      testGCode foreach testParser.process

      val outSet = outCmds.result().toSet

      require((expect -- outSet).isEmpty)
    }
  }
}
