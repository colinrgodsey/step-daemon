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

import com.colingodsey.stepd.GCode.SetPos
import com.colingodsey.stepd.Math.{Vec4, Epsilon}

object StepProcessor {
  case class SyncPos(pos: Vec4)

  case class ChunkMeta(speed: Int, writeSize: Boolean,
      steps: Option[Int], directions: Seq[Boolean])

  object PageFormat {
    case object SP_4x4D_128 extends PageFormat {
      val Directional = true
      val BytesPerChunk = 256
      val BytesPerSegment = 2
      val StepsPerSegment = 8
      val MaxStepsPerSegment = 7 //segment is 8 ticks, but has max of 7
      val SegmentsPerChunk = BytesPerChunk / BytesPerSegment
      val StepsPerChunk = SegmentsPerChunk * StepsPerSegment
    }

    case object SP_4x2_256 extends PageFormat {
      val Directional = false
      val BytesPerChunk = 256
      val StepsPerChunk = 1024
      val StepsPerSegment = 4
      val MaxStepsPerSegment = 3
    }

    case object SP_4x1_512 extends PageFormat {
      val Directional = false
      val BytesPerChunk = 256
      val StepsPerChunk = 512
      val StepsPerSegment = 1
      val MaxStepsPerSegment = 1
    }
  }
  trait PageFormat {
    val BytesPerChunk: Int
    val StepsPerSegment: Int
    val MaxStepsPerSegment: Int
    val StepsPerChunk: Int
    val Directional: Boolean
  }
}

abstract class StepProcessor(format: StepProcessor.PageFormat) {
  import StepProcessor._
  import format._

  def stepsPerMM: Vec4
  def ticksPerSecond: Int
  def leveling: MeshLevelingReader

  val currentChunk = new Array[Byte](BytesPerChunk)

  var stepPosX = 0L
  var stepPosY = 0L
  var stepPosZ = 0L
  var stepPosE = 0L

  var directionX = false
  var directionY = false
  var directionZ = false
  var directionE = false

  //var lastPos = Position.Zero

  var chunkIndex = 0
  var isLow = true // low or high nibble for SP_4x1_512
  var lastPos = Vec4.Zero
  var flowRate = 1.0
  var eAdvanceK = 0.0

  //stats
  def recordSplit(axis: Int): Unit

  def processChunk(chunk: Array[Byte], meta: ChunkMeta): Unit
  def process(syncPos: SyncPos): Unit

  def setPos(setPos: SetPos): Unit = {
    //TODO: if setting Z, should we reference the leveling offset?
    //val zOffs = leveling.getOffset(pos.x.toFloat, pos.y.toFloat)

    setPos.x.foreach(x => stepPosX = (x * stepsPerMM.x).round)
    setPos.y.foreach(y => stepPosY = (y * stepsPerMM.y).round)
    setPos.z.foreach(z => stepPosZ = (z * stepsPerMM.z).round)
    setPos.e.foreach(e => stepPosE = (e * stepsPerMM.e).round)
  }

  def getChunkSteps = {
    val steps = format match {
      case PageFormat.SP_4x4D_128 => chunkIndex / 2 * 8
      case PageFormat.SP_4x2_256 => chunkIndex * 4
      case PageFormat.SP_4x1_512 => chunkIndex * 2 + (if (isLow) 0 else 1)
    }

    // Return None if its a full chunk
    if (steps == format.StepsPerChunk) None
    else Some(steps)
  }

  def getChunkMeta = ChunkMeta(
    speed = ticksPerSecond,
    writeSize = !format.Directional,
    steps = getChunkSteps,
    directions = Seq(directionX, directionY, directionZ, directionE)
  )

  def flushChunk(): Unit = if(chunkIndex > 0 || !isLow) {
    val size = math.min(chunkIndex + 1, 256)
    val data = currentChunk.clone().take(size)

    processChunk(data, getChunkMeta)

    chunkIndex = 0
    isLow = true

    //sync real cartesian position after each trapezoid
    process(SyncPos(lastPos))
  }

  def getDirection(cur: Boolean, value: Byte) = value match {
    case 0 => cur
    case x => x > 0
  }

  def hasDirectionChanged(cur: Boolean, value: Byte) =
    getDirection(cur, value) != cur

  def checkDirection(dX: Byte, dY: Byte, dZ: Byte, dE: Byte): Unit = {
    val dirChanged =
      hasDirectionChanged(directionX, dX) ||
      hasDirectionChanged(directionY, dY) ||
      hasDirectionChanged(directionZ, dZ) ||
      hasDirectionChanged(directionE, dE)

    if (dirChanged) {
      flushChunk()

      directionX = getDirection(directionX, dX)
      directionY = getDirection(directionY, dY)
      directionZ = getDirection(directionZ, dZ)
      directionE = getDirection(directionE, dE)
    }
  }

  def processSegment(dX: Byte, dY: Byte, dZ: Byte, dE: Byte): Unit = {
    format match {
      case PageFormat.SP_4x4D_128 =>
        var a, b: Int = 0

        a |= (dX + 7) << 4
        a |=  dY + 7
        b |= (dZ + 7) << 4
        b |=  dE + 7

        currentChunk(chunkIndex) = a.toByte
        chunkIndex += 1
        currentChunk(chunkIndex) = b.toByte
        chunkIndex += 1
      case PageFormat.SP_4x2_256 =>
        checkDirection(dX, dY, dZ, dE)

        var byte: Int = 0

        byte |= math.abs(dX) << 6
        byte |= math.abs(dY) << 4
        byte |= math.abs(dZ) << 2
        byte |= math.abs(dE) << 0

        currentChunk(chunkIndex) = byte.toByte
        chunkIndex += 1
      case PageFormat.SP_4x1_512 =>
        checkDirection(dX, dY, dZ, dE)

        var byte: Int = if (isLow) 0 else currentChunk(chunkIndex)
        var nib: Int = 0

        if (dX != 0) nib |= (1 << 3)
        if (dY != 0) nib |= (1 << 2)
        if (dZ != 0) nib |= (1 << 1)
        if (dE != 0) nib |= (1 << 0)

        if (isLow) byte |= nib
        else byte |= nib << 4

        currentChunk(chunkIndex) = byte.toByte

        if (!isLow) chunkIndex += 1
        isLow = !isLow
    }

    if(chunkIndex == BytesPerChunk) flushChunk()
  }

  def processMove(dx: Int, dy: Int, dz: Int, de: Int): Unit = {
    if( math.abs(dx) > MaxStepsPerSegment || math.abs(dy) > MaxStepsPerSegment ||
        math.abs(dz) > MaxStepsPerSegment || math.abs(de) > MaxStepsPerSegment) {
      //we must break the delta into 2 moves
      val dxl = dx / 2
      val dyl = dy / 2
      val dzl = dz / 2
      val del = de / 2

      val dxr = dx - dxl
      val dyr = dy - dyl
      val dzr = dz - dzl
      val der = de - del

      if(math.abs(dx) > MaxStepsPerSegment) recordSplit(0)
      if(math.abs(dy) > MaxStepsPerSegment) recordSplit(1)
      if(math.abs(dz) > MaxStepsPerSegment) recordSplit(2)
      if(math.abs(de) > MaxStepsPerSegment) recordSplit(3)

      processMove(dxl, dyl, dzl, del)
      processMove(dxr, dyr, dzr, der)
    } else {
      processSegment(dx.toByte, dy.toByte, dz.toByte, de.toByte)
    }
  }

  def process(trap: MotionBlock): Unit = {
    val iter = trap.posIterator(ticksPerSecond.toDouble / StepsPerSegment, eAdvanceK)

    //run at actual tick rate
    for(pos <- iter) {
      val zOffset = leveling.getOffset(pos.x, pos.y)

      val stepPosXDest = (pos.x             * stepsPerMM.x           ).round
      val stepPosYDest = (pos.y             * stepsPerMM.y           ).round
      val stepPosZDest = ((pos.z + zOffset) * stepsPerMM.z           ).round
      val stepPosEDest = (pos.e             * stepsPerMM.e * flowRate).round

      val dx = stepPosXDest - stepPosX
      val dy = stepPosYDest - stepPosY
      val dz = stepPosZDest - stepPosZ
      val de = stepPosEDest - stepPosE

      processMove(dx.toInt, dy.toInt, dz.toInt, de.toInt)

      stepPosX = stepPosXDest
      stepPosY = stepPosYDest
      stepPosZ = stepPosZDest
      stepPosE = stepPosEDest

      lastPos = pos
    }
  }
}
