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
import com.colingodsey.stepd.Math._

import scala.concurrent.duration._

class PhysicsProcessorActor(val next: ActorRef, cfg: PlannerConfig) extends PhysicsProcessor
    with Actor with ActorLogging {
  var faultCounts = Map[MathFault, Int]()
  var acc = cfg.accel

  def recordFault(fault: MathFault): Unit = {
    val count = faultCounts.getOrElse(fault, 0) + 1

    faultCounts += fault -> count

    if(fault != LookaheadFault)
      log.debug("fault: {}", fault.toString)
    else
      log.debug("fault: {}", fault.toString)
  }

  def process(trap: MotionBlock): Unit = next ! trap

  def endTrapAndContinue(cmd: Any): Unit = {
    //send an empty move so we can finish the pending trap, maintain linearization
    flushDelta()
    next ! cmd
  }

  def jerk = cfg.jerk
  def sJerk = cfg.sJerk

  def receive: Receive = {
    case delta: MoveDelta if delta.isEOrZOnly =>
      flushDelta()
      process(delta)
      flushDelta()
    case delta: MoveDelta =>
      process(delta)

    case cmd @ SetMaxAcceleration(x, y, z, e) =>
      //TODO: this makes marlin freak out... why?
      //endTrapAndContinue(cmd)

      acc = Vec4(
        x.getOrElse(acc.x),
        y.getOrElse(acc.y),
        z.getOrElse(acc.z),
        e.getOrElse(acc.e)
      )

      log info s"Setting max acceleration to $acc"

    case setPos: SetPos =>
      endTrapAndContinue(setPos)
    case cmd: Command =>
      endTrapAndContinue(cmd)
  }
}
