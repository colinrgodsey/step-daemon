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

import akka.actor._
import com.colingodsey.stepd.GCode.Command
import com.colingodsey.stepd.PrintPipeline.{InputFlowSignal, TextResponse}
import com.colingodsey.stepd.planner.DeviceConfig
import com.colingodsey.stepd.serial.{LineSerial, Serial}

import scala.util.Try
import scala.concurrent.blocking

object SocatProxy {
  val socatLine = "socat -d -d pty,raw,echo=0 pty,raw,echo=0"

  val deviceBase = "/tmp/pty-stepd"
  val clientDevice = deviceBase + "-client"
  val serverDevice = deviceBase

  case class LineIn(str: String)
  case class PTY(dev: String)
}

class SocatProxy extends Actor with ActorLogging with Stash with LineParser with GCodeParser {
  import SocatProxy._
  import scala.sys.process._

  val logger = ProcessLogger(line => self ! LineIn(line))
  val ps = Process(socatLine).run(logger)

  var nLinked = 0
  var serialRef: Option[ActorRef] = None

  def processCommand(cmd: Command): Unit = {
    if (cmd.raw.cmd != "M110")
      context.system.eventStream.publish(cmd)
  }

  def linkingDone(): Unit = {
    log.info("linking done")

    val devConf = DeviceConfig(serverDevice, 250000)

    val ref = context.actorOf(Props(classOf[Serial], devConf), name = "proxy-serial")

    serialRef = Some(ref)

    ref ! LineSerial.Bytes("start\n")

    context become normal
  }

  def sendOk(n: Option[Int]): Unit = n match {
    case None =>
      serialRef.get ! LineSerial.Bytes("ok\n")
    case Some(n) =>
      serialRef.get ! LineSerial.Bytes(s"ok N$n\n")
  }

  def resume(): Unit = {
    log debug "resuming"

    context become normal
    unstashAll()

    for (ref <- serialRef) ref ! Serial.ResumeRead
  }

  def pause(): Unit = {
    log debug "pausing"

    context become paused

    for (ref <- serialRef) ref ! Serial.PauseRead
  }

  def common: Receive = {
    case Serial.Bytes(dat) =>
      //TODO: "sanitize '!' character"
      dat foreach process

    case TextResponse(str) if str.startsWith("ok N") =>
    case TextResponse(str) if str.startsWith("!") =>
    case TextResponse(str) =>
      log.info("out: {}", str)
      serialRef.get ! LineSerial.Bytes(str)
      serialRef.get ! LineSerial.Bytes("\n")
  }

  def paused: Receive = common orElse {
    case PrintPipeline.PauseInput =>
    case PrintPipeline.ResumeInput => resume()

    case PrintPipeline.DeviceRestart => resume()

    case _ => stash()
  }

  def normal: Receive = common orElse {
    case PrintPipeline.PauseInput => pause()
    case PrintPipeline.ResumeInput =>
  }

  def linking: Receive = {
    case PTY(_) if nLinked == 2 =>
      sys.error("this is... unexpected")
    case PTY(dev) =>
      val target = if(nLinked == 0) clientDevice else serverDevice

      nLinked += 1

      log.info("Linking {} to {}", dev, target)

      blocking(s"ln -s $dev $target".!)

      if(nLinked == 2) linkingDone()

    case LineIn(str) if str.contains("N PTY") =>
      val dev = str.split(' ').last.trim

      log.debug("Pty {}", dev)

      self ! PTY(dev)
    case LineIn(str) =>
      log.info(str)
  }

  def receive = linking

  def logWarn(e: Throwable): Unit = log.warning(e.toString)

  override def preStart(): Unit = {
    super.preStart()

    context.system.eventStream.subscribe(self, classOf[TextResponse])

    //incase we didnt shut down cleanly last time
    Try(s"rm $clientDevice".!)
    Try(s"rm $serverDevice".!)
  }

  override def postStop(): Unit = blocking {
    super.postStop()

    Try(s"rm $clientDevice".!).failed foreach logWarn
    Try(s"rm $serverDevice".!).failed foreach logWarn

    ps.destroy()
    ps.exitValue()
  }
}
