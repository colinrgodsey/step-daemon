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

import akka.actor._
import com.colingodsey.stepd.GCode._
import com.colingodsey.stepd.Math.Vec4
import com.colingodsey.stepd.MeshLevelingActor
import com.colingodsey.stepd.PrintPipeline.{PauseInput, ResumeInput}
import com.colingodsey.stepd.planner.StepProcessor.ChunkMeta

class StepProcessorActor(val next: ActorRef, cfg: PlannerConfig) extends StepProcessor(cfg.format)
    with Actor with ActorLogging with Stash {
  var hasSentSpeed = false

  var leveling = MeshLevelingReader.Empty
  var stepsPerMM = cfg.stepsPerMM

  val splits = new Array[Int](4)

  val ticksPerSecond = cfg.ticksPerSecond

  context.system.eventStream.subscribe(self, classOf[MeshLeveling.Reader])

  context.system.eventStream.publish(MeshLevelingActor.Load)

  def recordSplit(idx: Int): Unit = {
    splits(idx) = splits(idx) + 1

    log debug "split"
  }

  //should move to chunk manager?
  def processChunk(chunk: Array[Byte], meta: ChunkMeta): Unit =
    next ! Page(chunk, meta)

  def process(syncPos: StepProcessor.SyncPos): Unit =
    next ! syncPos

  def waitLeveling: Receive = {
    case x: MeshLeveling.Reader =>
      log info "done waiting on new mesh data"

      context become normal
      self ! x
      context.parent ! ResumeInput
      unstashAll()
    case _ => stash()
  }

  def normal: Receive = {
    case trap: MotionBlock =>
      process(trap)

    case x: MeshLeveling.Reader =>
      log info "got mesh leveling data"
      leveling = x

    case x: SetPos =>
      setPos(x)
      next ! x
    case ZProbe =>
      log info "waiting for leveling data"
      context become waitLeveling

      flushChunk()
      next ! ZProbe
      context.parent ! PauseInput

    case a @ SetStepsPerUnit(x, y, z, e) =>
      stepsPerMM = Vec4(
        x.getOrElse(stepsPerMM.x),
        y.getOrElse(stepsPerMM.y),
        z.getOrElse(stepsPerMM.z),
        e.getOrElse(stepsPerMM.e)
      )
      log info s"Settings steps/mm to $stepsPerMM"
      next ! a
    case a @ FlowRate(Some(x)) =>
      flowRate = x / 100.0
      log info s"setting flow rate scale to $flowRate"
      next ! a
    case a @ LinearAdvanceFactor(Some(x)) =>
      eAdvanceK = x
      log info s"setting linear advance factor to $eAdvanceK"
      next ! a

    case x: Command if x.isGCommand =>
      flushChunk()
      next ! x
    case x: Command =>
      next ! x
  }

  def receive = normal
}
