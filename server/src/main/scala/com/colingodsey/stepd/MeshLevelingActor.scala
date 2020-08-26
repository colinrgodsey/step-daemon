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

import java.io.FileNotFoundException

import akka.actor._
import com.colingodsey.stepd.GCode.Command
import com.colingodsey.stepd.PrintPipeline.TextResponse
import com.colingodsey.stepd.planner.{MeshLeveling, MeshLevelingConfig, MeshLevelingReader}
import com.colingodsey.stepd.serial.SerialDeviceActor
import org.json4s._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.{read, write}
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods

import scala.util.control.NonFatal

object MeshLevelingActor {
  val configPath = "bedlevel.json"

  case object Load
}

class MeshLevelingActor(cfg: MeshLevelingConfig) extends Actor with ActorLogging {
  import MeshLevelingActor._

  implicit val formats = Serialization.formats(NoTypeHints)

  var pointsBuffer = Set[MeshLeveling.Point]()
  var currentLeveling: Option[MeshLeveling] = None
  var isReading = false

  context.system.eventStream.subscribe(self, classOf[PrintPipeline.TextResponse])
  context.system.eventStream.subscribe(self, classOf[Command])
  context.system.eventStream.subscribe(self, Load.getClass)

  def loadFromFile(): Unit = try {
    val str = Util.readFile(configPath).trim

    require(pointsBuffer.isEmpty)

    if(str.nonEmpty) {
      val points = read[Seq[MeshLeveling.Point]](str)

      pointsBuffer ++= points

      flushPoints()
    }
  } catch {
    case _: FileNotFoundException =>
      log.info("No leveling data found.")
    case NonFatal(t) =>
      log.error(t, "Failed to load " + configPath)
  }

  def sendReader(): Unit = {
    val reader: MeshLevelingReader = currentLeveling match {
      case Some(x) => x.reader()
      case None => MeshLevelingReader.Empty
    }

    context.system.eventStream.publish(reader)
  }

  def saveToFile(): Unit = if(currentLeveling.isDefined) {
    val jsDoc = write(currentLeveling.get.points)

    Util.writeFile(configPath, jsDoc)
  } else log.warning("no meshleveling to save!")

  def flushPoints(): Unit = {
    log.info("processing bed level points")

    val newLeveling = MeshLeveling(pointsBuffer.toSeq, cfg.bedMaxX, cfg.bedMaxY)

    currentLeveling = Some(newLeveling)

    pointsBuffer = pointsBuffer.empty
    isReading = false

    sendReader()
    saveToFile()
    newLeveling.createImage("bedlevel.png")
  }

  def receive = {
    case TextResponse(MeshLeveling.OutputLine(point)) if isReading =>
      pointsBuffer += point
    case TextResponse(str) if isReading && str.startsWith("Bilinear Leveling Grid:") =>
      flushPoints()
    case TextResponse(line @ MeshLeveling.OutputLine(_)) =>
      log.warning("Not expecting bed point: " + line)
    case TextResponse("G29 Auto Bed Leveling") =>
      require(!isReading, "started bed leveling before finishing the last")

      log.info("gathering bed level points")

      isReading = true
    case _: TextResponse =>

    case Load =>
      log info "Loading mesh level data"
      loadFromFile()

    case _: Command =>
  }

}
