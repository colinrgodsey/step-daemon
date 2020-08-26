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

import akka.util.ByteString
import com.colingodsey.stepd.GCode.Command

object PrintPipeline {
  sealed trait Signal

  sealed trait InputFlowSignal extends Signal
  case object PauseInput extends InputFlowSignal
  case object ResumeInput extends InputFlowSignal

  case class Completed(cmd: Command)

  sealed trait Response
  case class TextResponse(str: String) extends Response
  case class ControlResponse(data: ByteString) extends Response

  //TODO: send event over bus, let persistent actors reset state safeleft
  case object DeviceRestart
}
