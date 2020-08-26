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

import com.colingodsey.stepd.Math.Vec4
import com.colingodsey.stepd.planner.{DeltaProcessor, MeshLevelingReader, PhysicsProcessor, StepProcessor}

object CartesianChunkIterator {
  type Response = Either[Array[Byte], GCode.Command]
}

abstract class CartesianChunkIterator(val gcode: String, format: StepProcessor.PageFormat)
      extends StepProcessor(format) with LineParser with GCodeParser with DeltaProcessor
      with PhysicsProcessor with Iterator[CartesianChunkIterator.Response] {
  private val gcodeIterator = gcode.iterator
  private var responseList = LazyList[CartesianChunkIterator.Response]()

  def leveling: MeshLevelingReader = MeshLevelingReader.Empty

  def recordSplit(axis: Int): Unit = {}

  def recordFault(fault: Math.MathFault): Unit = {}

  def process(syncPos: StepProcessor.SyncPos): Unit = {}

  def processCommand(cmd: GCode.Command): Unit = cmd match {
    case cmd: GCode.SetPos =>
      process(cmd)
      responseList :+= Right(cmd)
    case cmd: GCode.GMove =>
      process(cmd)
    case cmd =>
      responseList :+= Right(cmd)
  }

  def processChunk(chunk: Array[Byte], meta: StepProcessor.ChunkMeta): Unit =
    responseList :+= Left(chunk)

  def advanceData(): Unit = {
    while(responseList.isEmpty && gcodeIterator.hasNext)
      process(gcodeIterator.next())
  }

  def hasNext: Boolean = {
    advanceData()
    responseList.nonEmpty
  }

  def next(): CartesianChunkIterator.Response = {
    val chunk = responseList.head

    responseList = responseList.tail

    chunk
  }
}

object CartesianDeltaIterator {
  type Response = Either[Vec4, GCode.Command]

  def chunkIter(chunk: Array[Byte]): Iterator[Response] = new Iterator[Response] {
    var idx = 0

    def hasNext: Boolean = idx < chunk.length

    def next(): Response = {
      val a = chunk(idx) & 0xFF
      val b = chunk(idx + 1) & 0xFF

      val xRaw = (a & 0xF0) >> 4
      val yRaw = a & 0xF
      val zRaw = (b & 0xF0) >> 4
      val eRaw = b & 0xF

      idx += 2

      Left(Vec4(
        xRaw - 7,
        yRaw - 7,
        zRaw - 7,
        eRaw - 7
      ))
    }
  }

  def apply(itr: CartesianChunkIterator): Iterator[Response] = itr flatMap {
    case Left(chunk) =>
      chunkIter(chunk)
    case Right(cmd) =>
      Seq(Right(cmd)).iterator
  }
}
