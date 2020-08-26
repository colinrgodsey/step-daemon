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

package com.colingodsey.stepd.serial

import akka.actor._
import akka.util.ByteString
import com.colingodsey.stepd.PrintPipeline.{ControlResponse, TextResponse}
import com.colingodsey.stepd.planner.DeviceConfig

object LineSerial {
  type Bytes = Serial.Bytes
  val Bytes = Serial.Bytes

  val ControlChar = '!'
  val ControlLineLength = 4 + 1
}

class LineSerial(cfg: DeviceConfig) extends Actor with ActorLogging with Stash {
  import LineSerial._

  log.info("starting...")

  val serial = context.actorOf(
    Props(classOf[Serial], cfg),
    name = "serial")

  val dataBuffer = new Array[Byte](4096)
  var bufferIdx = 0
  var isControl = false

  def processStr(str: String): Unit = {
    log.debug("recv: {}", str)
    context.parent ! TextResponse(str)
  }

  def bufferByte(b: Byte): Unit = {
    dataBuffer(bufferIdx) = b
    bufferIdx += 1
  }

  def processByte(b: Byte): Unit = b.toChar match {
    case '\r' | '\n' if bufferIdx == 0 => // skip
    case ControlChar if bufferIdx == 0 => // control char on new line
      isControl = true
      bufferByte(b)
    case '\r' | '\n' if !isControl =>
      processStr(new String(dataBuffer, 0, bufferIdx, "UTF-8"))
      bufferIdx = 0
    case '\r' | '\n' if isControl && (bufferIdx > ControlLineLength) =>
      val bytes = ByteString(dataBuffer.slice(1, bufferIdx))

      if (bytes.length == ControlLineLength) {
        context.parent ! ControlResponse(bytes)
        isControl = false
        bufferIdx = 0
      } else {
        log.warning("Malformed control line")
      }

    case _ => bufferByte(b)
  }

  def receive = {
    case Bytes(data) if sender == serial => data foreach processByte
    case x: Bytes => serial ! x
  }
}