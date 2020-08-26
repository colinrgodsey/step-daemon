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

import java.io.File

import com.colingodsey.stepd.Math._
import com.colingodsey.stepd.planner.{DeviceConfig, MeshLevelingConfig, PlannerConfig, StepProcessor}
import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters._

object ConfigMaker {
  lazy val config = ConfigFactory.parseFile(new File("./config.conf")) withFallback
      ConfigFactory.load()

  lazy val stepd = config.getConfig("com.colingodsey.stepd")
  lazy val planner = stepd.getConfig("planner")
  lazy val fa = stepd.getConfig("fil-advance")
  lazy val device = stepd.getConfig("device")
  lazy val bed = stepd.getConfig("bed")

  def plannerConfig = {
    val accel = planner.getDoubleList("acceleration").asScala
    val jerk = planner.getDoubleList("jerk").asScala
    val sJerk = planner.getDoubleList("sjerk").asScala
    val stepsPerMM = planner.getDoubleList("steps-per-mm").asScala

    PlannerConfig(
      accel = Vec4(accel(_)),
      jerk = Vec4(jerk(_)),
      sJerk = Vec4(sJerk(_)),
      stepsPerMM = Vec4(stepsPerMM(_)),
      ticksPerSecond = planner.getInt("ticks-per-second"),
      format = planner.getString("format").toLowerCase match {
        case "sp_4x4d_128" => StepProcessor.PageFormat.SP_4x4D_128
        case "sp_4x2_256" => StepProcessor.PageFormat.SP_4x2_256
        case "sp_4x1_512" => StepProcessor.PageFormat.SP_4x1_512
        case x => sys.error("Unknown page format " + x)
      }
    )
  }

  def deviceConfig = {
    DeviceConfig(
      dev = device.getString("dev"),
      baud = device.getInt("baud")
    )
  }

  def levelingConfig = {
    MeshLevelingConfig(
      bedMaxX = bed.getInt("max-x"),
      bedMaxY = bed.getInt("max-y")
    )
  }
}
