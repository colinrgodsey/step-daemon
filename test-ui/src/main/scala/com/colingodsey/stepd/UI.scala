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

import com.colingodsey.stepd.Math._

import akka.actor._
import akka.util.ByteString
import akka.io.{ IO, Tcp }
import akka.util.ByteString

import org.json4s

import java.net.InetSocketAddress

import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

class UI extends Application {
  var t = 0.0
  var lastDeadline = Deadline.now
  var trapIdx = 0
  var lastPos = Vec4.Zero

  val speedScale = 0.5
  val spatialScale = 10.0 // 20.0

  val root = new StackPane
  val scene = new Scene(root, 800, 600)
  val circle = new Circle()
  val timer = new AnimationTimer {
    def handle(now: Long): Unit = {
      val now = Deadline.now
      val dt = (now - lastDeadline).toMillis / 1000.0

      var pos = lastPos

      if(MovementProcessor.f != null) {
        MovementProcessor.f(dt * speedScale) foreach { x =>
          //if(math.random < 0.05) println(x)
          pos = x
        }
      }

      render(pos, dt)
      lastPos = pos
      lastDeadline = now
    }
  }

  root.getChildren.add(circle)

  override def start(primaryStage: Stage): Unit = {
    primaryStage.setTitle("Planner Debug")

    primaryStage.setScene(scene)
    primaryStage.show()

    timer.start()

    circle.setRadius(5)
  }

  def render(pos: Vec4, dt: Double): Unit = {
    val v = (pos - lastPos).length / dt
    val c = ((v / 200.0) * (1024 + 256) / speedScale).toInt
    val r = math.min(c, 255)
    val g = math.max(math.min(c - 255, 255), 0)
    val b = math.max(math.min(c - 1024, 255), 0)

    circle.setFill(Color.rgb(r - b, g, b))
    circle.setTranslateX((pos.x - 100) * spatialScale)
    circle.setTranslateY((pos.y - 100) * spatialScale)
  }
}

object UI extends App {
  val system = ActorSystem()

  system.actorOf(Props[UIActor], name="pipeline")

  sys.addShutdownHook {
    system.terminate()

    Await.result(system.whenTerminated, 10.seconds)
  }

  Application.launch(classOf[UI], args: _*)
}

class UIActor extends Actor with ActorLogging  {
  import Tcp._
  import context.system

  val manager = IO(Tcp)

  IO(Tcp) ! Bind(self, new InetSocketAddress("0.0.0.0", 6498))

  def receive = {
    case b @ Bound(localAddress) =>
      log.info(s"bound to $localAddress")
      context.parent ! b

    case CommandFailed(_: Bind) =>
      log.error("bind failed!")
      context.stop(self)

    case Connected(remote, local) =>
      val handler = context.actorOf(Props(classOf[MovementProcessor], sender))

      sender ! Register(handler)
  }

  override def postStop(): Unit = {
    super.postStop()
    system.terminate()
  }
}

case class PageData(idx: Int, data: ByteString)

object MovementProcessor {
  var f: Double => Option[Vec4] = null
}

class MovementProcessor(conn: ActorRef) extends Actor with ActorLogging {
  import Tcp._
  import GCode._
  import com.colingodsey.stepd.Math._

  var ticks = 70000 / 4 * 80.0 //180.0 * ticksPerSecond / format.StepsPerSegment
  var idx = 0

  var curChunkIdx = -1
  var curChunkSteps = 0

  var stepsPer: Vec4 = null
  var ticksPerSecond: Int = 0
  var format: PageFormat = null

  var lastPageSpeed = 0
  var lastDirection = Seq.fill[Boolean](4)(false)
  var pages = Map[Int, PageData]()

  val stash = mutable.Queue[GCode.Command]()

  var x = 0L
  var y = 0L
  var z = 0L
  var e = 0L

  @volatile var pos = Vec4.Zero //(100, 100, 5, 0)

  val lineSerial = context.actorOf(Props[LineSerial])

  MovementProcessor.f = getPos(_)

  def getDirectionScale(axis: Int) =
    if (lastDirection(axis)) 1
    else -1

  def takeStep(): Unit = {
    require(pages contains curChunkIdx, "using idx of missing page")
    val curChunk = pages(curChunkIdx).data

    format match {
      case PageFormat.SP_4x4D_128 =>
        val blockIdx = (idx / 7) << 1

        val a = curChunk(blockIdx) & 0xFF
        val b = curChunk(blockIdx + 1) & 0xFF

        val xRaw = (a & 0xF0) >> 4
        val yRaw = a & 0xF
        val zRaw = (b & 0xF0) >> 4
        val eRaw = b & 0xF

        x += xRaw - 7
        y += yRaw - 7
        z += zRaw - 7
        e += eRaw - 7

        idx += 7
      case PageFormat.SP_4x2_256 =>
        val byte = curChunk(idx / 3) & 0xFF

        val xRaw = (byte >> 6) & 0x3
        val yRaw = (byte >> 4) & 0x3
        val zRaw = (byte >> 2) & 0x3
        val eRaw = (byte >> 0) & 0x3

        x += xRaw * getDirectionScale(0)
        y += yRaw * getDirectionScale(1)
        z += zRaw * getDirectionScale(2)
        e += eRaw * getDirectionScale(3)

        idx += 3
      case PageFormat.SP_4x1_512 =>
        val byte = curChunk(idx >> 1) & 0xFF
        val isLow = (idx & 0x1) == 0

        val nibble = if(isLow) byte & 0xF else byte >> 4

        val xRaw = (nibble >> 3) & 0x1
        val yRaw = (nibble >> 2) & 0x1
        val zRaw = (nibble >> 1) & 0x1
        val eRaw = (nibble >> 0) & 0x1

        x += xRaw * getDirectionScale(0)
        y += yRaw * getDirectionScale(1)
        z += zRaw * getDirectionScale(2)
        e += eRaw * getDirectionScale(3)

        idx += 1
    }

    pos = Vec4(x / stepsPer.x, y / stepsPer.y, z / stepsPer.z, e / stepsPer.e)

    if(idx == curChunkSteps) {
      idx = 0
      pages -= curChunkIdx
      curChunkIdx = -1
      ackPages()
    }
  }

  def getPos(dt: Double): Option[Vec4] = {
    val x = pos

    if (format != null) {
      self ! Tick(dt * ticksPerSecond / format.StepsPerSegment)
    }

    Some(x)
  }

  def consumeTicks(): Unit = {
    consumeCommands()
    while(ticks >= 1 && curChunkIdx != -1) {
      takeStep()
      ticks -= 1
      consumeCommands()
    }
  }

  def ackPages(): Unit = {
    val Free = 0
    val Ok = 2
    val dat = new Array[Byte](4)

    for (i <- 0 until 16) {
      val x = pages contains i match {
        case true => Ok
        case false => Free
      }
      val byteIdx = i / 4
      val bitIdx = (i * 2) % 8
      val b = dat(byteIdx) & 0xFF
      dat(byteIdx) = (b | (x << bitIdx)).toByte
    }

    val chs = (dat.foldLeft(0)(_ ^ _) & 0xFF).toByte
    val out = ByteString('!') ++ ByteString(dat) ++ ByteString(chs)
    conn ! Write(out)

  }

  def consumeCommands(): Unit = {
    while (curChunkIdx == -1 && stash.nonEmpty) {
      process(stash.removeHead())
      conn ! Write(ByteString("ok\n"))
    }
  }

  def parseConfig(cfgStr: String): Unit = {
    import json4s._
    import json4s.native.JsonMethods._

    val v = parse(cfgStr).asInstanceOf[JObject]

    def toDoub(x: JValue): Double = x match {
      case x: JInt => x.num.toDouble
      case x: JDouble => x.num
    }

    val ssm = (v \\ "steps-per-mm").asInstanceOf[JArray].arr.map(toDoub)
    val tps = toDoub(v \\ "ticks-per-second")
    val fmt = (v \\ "format") match {
      case JString("SP_4x4D_128") => PageFormat.SP_4x4D_128
      case JString("SP_4x2_256") => PageFormat.SP_4x2_256
      case JString("SP_4x1_512") => PageFormat.SP_4x1_512
    }

    stepsPer = Vec4(ssm)
    ticksPerSecond = tps.toInt
    format = fmt

    conn ! Write(ByteString("pages_ready\n"))
  }

  def process(page: PageData): Unit = {
    require(!pages.contains(page.idx), "got overlapping page data")
    pages += page.idx -> page
    ackPages()
  }

  def process(cmd: GDirectMove): Unit = {
    lastPageSpeed = cmd.speed getOrElse lastPageSpeed
    if (cmd.index == None) return
    curChunkIdx = cmd.index.get
    curChunkSteps = cmd.steps getOrElse format.StepsPerChunk
    lastDirection = Seq(
      cmd.xDir getOrElse lastDirection(0),
      cmd.yDir getOrElse lastDirection(1),
      cmd.zDir getOrElse lastDirection(2),
      cmd.eDir getOrElse lastDirection(3),
    )
  }

  def process(cmd: GCode.Command): Unit = cmd match {
    case cmd: GDirectMove => process(cmd)
    case GetPos =>
      log.info("sending pos")
      val str = "X:0.00 Y:0.00 Z:10.00 E:0.00 Count X:0 Y:0 Z:16000"
      conn ! Write(ByteString(s"$str\n"))
    case _ =>
  }

  def receive: Receive = {
    case Received(data) => lineSerial ! data

    case TextResponse(str) =>
      if (str(0) == '{') {
        parseConfig(str)
      } else {
        stash += GCode(str)
      }
      consumeTicks()
    case ControlResponse(id, data) =>
      process(PageData(id, data))
      consumeTicks()

    case Tick(x) =>
      ticks += x
      consumeTicks()
  }

  case class Tick(ticks: Double)
}
