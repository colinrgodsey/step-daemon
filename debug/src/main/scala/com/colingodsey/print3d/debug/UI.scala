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

package com.colingodsey.print3d.debug

import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.stage.Stage
import akka.actor._
import akka.util.ByteString
import com.colingodsey.print3d.debug.UI.system
import com.colingodsey.stepd.GCode.{Command, SetPos}
import com.colingodsey.stepd._
import com.colingodsey.stepd.Math._
import com.colingodsey.stepd.PrintPipeline.{PauseInput, ResumeInput}
import com.colingodsey.stepd.planner.StepProcessor.PageFormat
import com.colingodsey.stepd.planner._

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
          if(math.random < 0.05) println(x)
          pos = x
        }
      }

      render(pos, dt)

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

    lastPos = pos
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

class UIActor extends Actor {
  val movement = context.actorOf(Props(classOf[MovementProcessor]), name="motion-ui")
  val steps = context.actorOf(Props(classOf[StepProcessorActor], movement, ConfigMaker.plannerConfig), name="steps")
  val physics = context.actorOf(Props(classOf[PhysicsProcessorActor], steps, ConfigMaker.plannerConfig), name="physics")
  val delta = context.actorOf(Props(classOf[DeltaProcessorActor], physics, true), name="delta")

  val proxy = context.actorOf(Props[SocatProxy], name="proxy")
  val bedlevel = context.actorOf(Props(classOf[MeshLevelingActor], ConfigMaker.levelingConfig), name="bed-leveling")

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  var pausers = Set[ActorRef]()

  context.children foreach context.watch

  context.system.eventStream.subscribe(self, classOf[Command])

  checkPause()

  def checkPause(): Unit = {
    val msg = if (pausers.isEmpty) ResumeInput else PauseInput

    proxy ! msg
  }

  def receive = {
    case Terminated(_) =>
      sys.error("Child terminated, restarting pipeline")
      system.terminate()

    // input flow control
    case PauseInput =>
      pausers += sender
      checkPause()
    case ResumeInput =>
      pausers -= sender
      checkPause()

    case x: Command => delta.tell(x, sender)
  }

  override def postStop(): Unit = {
    super.postStop()
    system.terminate()
  }
}

object MovementProcessor {
  var f: Double => Option[Vec4] = null
}

class MovementProcessor extends Actor with ActorLogging {
  import com.colingodsey.stepd.Math._

  val stepsPer = ConfigMaker.plannerConfig.stepsPerMM
  val ticksPerSecond = ConfigMaker.plannerConfig.ticksPerSecond
  val format = ConfigMaker.plannerConfig.format

  val stash = mutable.Queue[Any]()

  var ticks = 180.0 * ticksPerSecond / format.StepsPerSegment
  var idx = 0

  var curChunk: ByteString = null
  var curChunkSteps = 0

  var lastPageSpeed = ticksPerSecond
  var lastDirection = Seq.empty[Boolean]

  var x = 0L
  var y = 0L
  var z = 0L
  var e = 0L

  @volatile var pos = Vec4.Zero //(100, 100, 5, 0)

  MovementProcessor.f = getPos(_)

  def getDirectionScale(axis: Int) =
    if (lastDirection(axis)) 1
    else -1

  def takeStep(): Unit = {
    format match {
      case PageFormat.SP_4x4D_128 =>
        val blockIdx = (idx >> 3) << 1

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

        idx += 8
      case PageFormat.SP_4x2_256 =>
        val byte = curChunk(idx >> 2) & 0xFF

        val xRaw = (byte >> 6) & 0x3
        val yRaw = (byte >> 4) & 0x3
        val zRaw = (byte >> 2) & 0x3
        val eRaw = (byte >> 0) & 0x3

        x += xRaw * getDirectionScale(0)
        y += yRaw * getDirectionScale(1)
        z += zRaw * getDirectionScale(2)
        e += eRaw * getDirectionScale(3)

        idx += 4
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
      curChunk = null

      checkStash()
    }
  }

  def getPos(dt: Double): Option[Vec4] = {
    val x = pos

    self ! Tick(dt * ticksPerSecond / format.StepsPerSegment)

    Some(x)
  }

  def consumeBuffer(): Unit = {
    while(ticks >= 1 && curChunk != null) {
      takeStep()

      ticks -= 1
    }
  }

  def checkStash(): Unit = {
    while (curChunk == null && stash.nonEmpty)
      receiveInput(stash.removeHead())

    if (stash.nonEmpty) context.parent ! PauseInput
    else context.parent ! ResumeInput
  }

  def receiveInput: Receive = {
    case Page(buff: ByteString, meta) if curChunk == null =>
      curChunk = buff
      curChunkSteps = meta.steps getOrElse format.StepsPerChunk

      lastPageSpeed = meta.speed
      lastDirection = meta.directions

      consumeBuffer()
    case setPos: SetPos if curChunk == null =>
      x = (setPos.x.getOrElse(pos.x) * stepsPer.x).round
      y = (setPos.y.getOrElse(pos.y) * stepsPer.y).round
      z = (setPos.z.getOrElse(pos.z) * stepsPer.z).round
      e = (setPos.e.getOrElse(pos.e) * stepsPer.e).round
  }

  def receive: Receive = {
    case x: Page =>
      stash += x
      checkStash()
    case x: SetPos =>
      stash += x
      checkStash()

    case Tick(x) =>
      ticks += x

      consumeBuffer()

    case _: StepProcessor.SyncPos =>
    case _: Command =>
  }

  case class Tick(ticks: Double)
}

class MovementProcessorPos extends Actor with Stash with ActorLogging {
  import com.colingodsey.stepd.Math._

  case object Unstash

  var t = 0.0
  var trap: MotionBlock = null

  MovementProcessor.f = getPos(_)

  def processTrap(dt: Double): Vec4 = synchronized {
    val d = trap.getPos(t)

    val pos = trap.move.from + trap.move.d.normal * d

    t += dt

    if(t >= trap.time) {
      t -= trap.time

      trap = null

      self ! Unstash
    }

    pos
  }

  def getPos(dt: Double) = synchronized {
    if(trap == null) {
      t += dt
      None
    } else Some(processTrap(dt))
  }

  def receive: Receive = {
    case x: MotionBlock if trap == null =>
      synchronized {
        trap = x
        processTrap(0.0)
      }

    case _: MotionBlock => stash()
    case Unstash => unstashAll()

    case _: Command =>
  }
}
