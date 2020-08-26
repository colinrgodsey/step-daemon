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
import com.colingodsey.stepd.GCode.{Command, ReportSettings}
import com.colingodsey.stepd.PrintPipeline
import com.colingodsey.stepd.PrintPipeline.{Completed, Response, TextResponse}
import com.colingodsey.stepd.planner.DeviceConfig

import scala.concurrent.duration._

object SerialDeviceActor {
  val MaxQueue = 4
  //val MaxQueue = 1
  val MaxIncr = 99
  val NumReset = "M110"

  case object ResetNumbering
}

class SerialDeviceActor(cfg: DeviceConfig) extends Actor with Stash with ActorLogging {
  import SerialDeviceActor._

  var nIncr = 1
  var pending = Map[Int, (ActorRef, Command)]()

  val lineSerial: ActorRef = context.actorOf(
    Props(classOf[LineSerial], cfg),
    name = "line-serial")

  def sendCommand(str: String) = {
    val str0 = s"N$nIncr $str"
    val bytes = ByteString.fromString(str0)
    val check = bytes.foldLeft(0)(_ ^ _) & 0xFF
    val finalBytes = bytes ++ ByteString.fromString(s"*$check\n")

    //log.info("send: {}*{} ({})", str0, check, pending.size)

    log.debug("send: {}*{}", str0, check)

    lineSerial ! Serial.Bytes(finalBytes)
  }

  def resetNumbering(): Unit = {
    nIncr = 0
    sendCommand(NumReset)
    nIncr += 1
  }

  def receive = {
    case _: Command if pending.size >= MaxQueue =>
      stash()
    case cmd: Command =>
      val str = cmd.toString

      if(nIncr > MaxIncr) {
        resetNumbering()
      }

      sendCommand(str)
      pending += nIncr -> (sender, cmd)

      nIncr += 1

    case ResetNumbering =>
      resetNumbering()

    case x: LineSerial.Bytes =>
      lineSerial ! x

    case x: Serial.FlowCommand =>
      lineSerial ! x

    case PrintPipeline.DeviceRestart =>
      log.info("Resetting command state")
      pending = Map.empty

    case a @ TextResponse(str) if str.startsWith("echo:start") =>
      resetNumbering()
      context.system.eventStream.publish(a)
    case a @ TextResponse(str) if str.startsWith("ok N") || str.startsWith("ok T") =>
      unstashAll()

      log.debug("ok: {}", str)

      if (str.startsWith("ok N")) {
        val n = str.drop(4).takeWhile(_ != ' ').toInt

        pending get n match {
          case None if n > 1 =>
            log.warning("Got an ok, but no sender to respond to")
          case None =>
          case Some((ref, cmd)) =>
            ref ! Completed(cmd)
        }

        pending -= n
      }

      def removeCmd(cmdStr: String) = pending.filter {
        case (_, (_, cmd)) => cmd.raw.cmd == cmdStr
      }.headOption match {
        case Some((n, (ref, cmd))) =>
          log.info("removing a pending {}", cmdStr)
          ref ! Completed(cmd)
          pending -= n
        case _ =>
      }

      //goofy responses
      if(str.startsWith("ok T")) {
        context.system.eventStream.publish(a)

        removeCmd("M105")
      }
    case TextResponse(str) if str.startsWith("ok N") =>
      log.warning("Got an ok for no reason: {}", str)
    case TextResponse(str) if str.startsWith("ok") =>
      log.warning("Random ok with no N value: {}", str)

    //any other response we broadcast out to our subscribers
    case a: Response => context.system.eventStream.publish(a)
  }
}