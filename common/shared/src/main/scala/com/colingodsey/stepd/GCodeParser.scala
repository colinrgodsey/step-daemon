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

import com.colingodsey.stepd.GCode._

trait GCodeParser {
  def processCommand(cmd: Command): Unit

  //TODO: the command modification should maybe be moved out of here
  def process(raw: Raw): Unit = {
    val out: Command = raw.cmd match {
      case "G0" | "G1" => GMove(raw)
      case "G28" =>
        //get position after homing
        processCommand(Home(raw))
        GetPos
      case "G29" =>
        //send the verbose version of the command
        processCommand(ZProbe)
        //home afterwards
        processCommand(Home.All)
        GetPos
      case "G90" => SetAbsolute
      case "G91" => SetRelative
      case "G92" => SetPos(raw)

      case "M92" => SetStepsPerUnit(raw)
      case "M114" => GetPos
      case "M201" => SetMaxAcceleration(raw)
      case "M203" => SetMaximumFeedRate(raw)
      case "M220" => FeedRate(raw)
      case "M221" => FlowRate(raw)
      case "M503" => ReportSettings
      case "M900" => LinearAdvanceFactor(raw)

      case _ => raw
    }

    processCommand(out)
  }
}
