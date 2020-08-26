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

import scala.scalajs.js
import org.scalajs.dom
import org.scalajs.dom.html

import scala.scalajs.js.annotation.JSExport

object WebPlayer extends js.JSApp {
  var width = 1
  var height = 1
  var acc = Vec4(2000, 1500, 100, 10000) / 2.0
  var jerk = Vec4(15, 10, 0.4f, 5)
  var stepsPerMM = Vec4(80, 80, 1600, 95.2)
  var ticksPerSecond: Int = 30000

  var pos = Vec4.Zero
  var realPos = Vec4.Zero
  var offset = Vec4.Zero
  var lastStamp = -1.0
  var vel = 0.0
  var zoomScale = 4.0
  var keysDown = Set[String]()
  var isMouseDown = false
  var lastMousePos = Vec4.Zero
  var minX = 10000000.0
  var maxX = 0.0
  var minY = 10000000.0
  var maxY = 0.0

  var gcodeItr: Option[Iterator[CartesianDeltaIterator.Response]] = None
  var ticks = 0.0

  val zoomSpeed = 0.01
  val speedScale = 1.0
  val offsetMoveSpeed = 12
  val posDiv = dom.document.getElementById("pos")

  val canvas = dom.document.getElementById("main-canvas") match {
    case x: html.Canvas => x
    case null => sys.error("couldnt find canvas")
    case _ => sys.error("Expected canvas!")
  }

  val ctx = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]

  val reader = new dom.FileReader

  def main(): Unit = {
    setDims()

    ctx.clearRect(0, 0, width, height)

    zoomScale = math.min(width, height) / 200.0

    dom.window.addEventListener("resize", resizeCanvas _, false)

    dom.document.addEventListener("keydown", keyDown _, false)
    dom.document.addEventListener("keyup", keyUp _, false)
    dom.document.addEventListener("wheel", wheelEvent _, false)
    dom.document.addEventListener("mousedown", mouseDown _, false)
    dom.document.addEventListener("mouseup", mouseUp _, false)
    dom.document.addEventListener("mousemove", mouseMove _, false)

    reader.addEventListener("loadend", fileLoaded _, false)

    dom.window.setInterval({ () =>
      tickPosition(0.0)
    }, 1000)

    requestAnimate()
  }

  def resizeCanvas(event: js.Any): Unit = setDims()

  def keyDown(event: dom.KeyboardEvent): Unit = keysDown += event.key

  def keyUp(event: dom.KeyboardEvent): Unit = keysDown -= event.key

  def mouseDown(event: dom.MouseEvent): Unit = isMouseDown = true

  def mouseUp(event: dom.MouseEvent): Unit = isMouseDown = false

  def mouseMove(event: dom.MouseEvent): Unit = {
    val newMousePos = Vec4.X * event.screenX + Vec4.Y * event.screenY

    if(isMouseDown) {
      val delta = newMousePos - lastMousePos

      offset += delta
    }

    lastMousePos = newMousePos
  }

  def processKey(key: String, dts: Double): Unit = key match {
    case "ArrowUp" | "w" =>
      offset += Vec4.Y * offsetMoveSpeed * dts
    case "ArrowDown" | "s" =>
      offset -= Vec4.Y * offsetMoveSpeed * dts
    case "ArrowLeft" | "a" =>
      offset += Vec4.X * offsetMoveSpeed * dts
    case "ArrowRight" | "d"=>
      offset -= Vec4.X * offsetMoveSpeed * dts
    case "+" | "=" =>
      zoomScale += zoomSpeed * dts
    case "-" =>
      zoomScale -= zoomSpeed * dts
      zoomScale = math.max(zoomScale, 0.0001)
    case _ =>
  }

  def wheelEvent(event: dom.WheelEvent): Unit = {
    zoomScale -= zoomSpeed * event.deltaY
    zoomScale = math.max(zoomScale, 0.0001)
  }

  def fileLoaded(event: dom.ProgressEvent): Unit = {
    val gcodeString = (reader.result: Any) match {
      case str: String => str
      case x => sys.error("Expected string, not " + x)
    }
    val itr0 = new GCodeIterator(gcodeString)

    gcodeItr = Some(CartesianDeltaIterator(itr0))
    ticks = 0
    pos = Vec4.Zero
    realPos = Vec4.Zero
  }

  def prettyDouble(x: Double): String =
    ((x * 1000).toInt / 1000.0).toString

  def onAnimate(msStamp: Double): Unit = {
    if(lastStamp == -1) lastStamp = msStamp

    val dtS = (msStamp - lastStamp) / 1000.0

    if(dtS == 0) {
      requestAnimate()
      return
    }

    keysDown.foreach(processKey(_, dtS))

    tickPosition(dtS * speedScale)

    posDiv.innerHTML = s"Z: ${prettyDouble(realPos.z)} E: ${prettyDouble(realPos.e)} <br />" +
      s"Offset: $offset <br /> Scale: ${prettyDouble(zoomScale)}"

    val c = ((vel / 200.0) * (1024 + 256) / speedScale).toInt
    val r = math.min(c, 255)
    val g = math.max(math.min(c - 255, 255), 0)
    val b = math.max(math.min(c - 1024, 255), 0)
    //val displayPos = (realPos + offset)// * zoomScale

    ctx.save()

    ctx.fillStyle = "rgba(255, 255, 255, 0.6)"
    ctx.fillRect(0, 0, width, height)

    ctx.translate(offset.x, offset.y)
    ctx.scale(zoomScale, zoomScale)

    ctx.lineWidth = 0.5
    ctx.strokeStyle = "black"
    ctx.strokeRect(0, 0, 200, 200)

    ctx.strokeStyle = "blue"
    ctx.strokeRect(minX, minY, maxX - minX, maxY - minY)

    ctx.beginPath()
    ctx.fillStyle = s"rgba(${r - b}, $g, $b, 1)"
    ctx.arc(realPos.x, realPos.y, 0.5, 0, 2 * math.Pi)
    ctx.fill()

    ctx.restore()

    requestAnimate()

    lastStamp = msStamp
  }

  def tickPosition(dtS: Double): Unit = {
    val oldPos = realPos
    var ticksDone = 0

    ticks += dtS * ticksPerSecond / 8.0

    while(ticks >= 1 && ticksDone < 40000) {
      tickOneSegment()
      ticks -= 1
      ticksDone += 1
    }

    vel = (oldPos - realPos).length / dtS
  }

  def checkBounds(): Unit = if(realPos.e > 0) {
    val x = realPos.x
    val y = realPos.y

    if(x < minX) minX = x
    if(x > maxX) maxX = x
    if(y < minY) minY = y
    if(y > maxY) maxY = y
  }

  def tickOneSegment(): Unit = if(gcodeItr.isDefined) {
    if(!gcodeItr.get.hasNext) {
      gcodeItr = None
    } else {
      gcodeItr.get.next() match {
        case Left(delta) =>
          pos += delta
          realPos = Vec4(
            pos.x / stepsPerMM.x,
            pos.y / stepsPerMM.y,
            pos.z / stepsPerMM.z,
            pos.e / stepsPerMM.e
          )

          checkBounds()
        case Right(_: GCode.SetPos) =>
          tickOneSegment()
        case Right(cmd) =>
          println(cmd)
          tickOneSegment()
      }
    }
  }

  def requestAnimate() =
    dom.window.requestAnimationFrame(onAnimate _)

  def setDims(): Unit = {
    width = dom.window.innerWidth.toInt
    height = dom.window.innerHeight.toInt

    canvas.width = width
    canvas.height = height
  }

  @JSExport def handleFiles(files: dom.FileList): Unit = {
    reader.readAsText(files(0))
  }

  class GCodeIterator(gcode: String) extends CartesianChunkIterator(gcode) {
    def acc = WebPlayer.acc
    def jerk = WebPlayer.jerk
    def stepsPerMM = WebPlayer.stepsPerMM
    def ticksPerSecond = WebPlayer.ticksPerSecond
  }
}
