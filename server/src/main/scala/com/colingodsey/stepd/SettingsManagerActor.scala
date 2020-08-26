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

import akka.actor._

import com.colingodsey.stepd.GCode._
import com.colingodsey.stepd.PrintPipeline.TextResponse

/**
 * Simple actor that syncs device settings once it starts.
 *
 * @param next Next actor in the pipeline.
 */
class SettingsManagerActor(next: ActorRef) extends Actor with ActorLogging with GCodeParser {
  context.system.eventStream.subscribe(self, classOf[TextResponse])

  def processEchoCommand(str: String): Unit = process(Raw(str.drop(5).trim))

  def processCommand(cmd: Command): Unit = {
    log debug s"Sync command: $cmd"
    next ! cmd
  }

  def receive = {
    case TextResponse(str) if str.startsWith("pages_ready") =>
      log info "Asking device for current settings"
      next ! ReportSettings // ask device to report current settings

    case TextResponse(str) if str.startsWith("echo: M92 ") => processEchoCommand(str)
    case TextResponse(str) if str.startsWith("echo:  M203 ") => processEchoCommand(str)
    case TextResponse(str) if str.startsWith("echo:  M201 ") => processEchoCommand(str)

    case TextResponse(_) =>
  }
}
