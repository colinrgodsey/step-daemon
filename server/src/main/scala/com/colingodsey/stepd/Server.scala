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
import akka.util.ByteString
import com.colingodsey.stepd.GCode.Command
import com.colingodsey.stepd.planner._
import com.colingodsey.stepd.serial.{LineSerial, SerialDeviceActor}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import scala.util.control.NoStackTrace

/**
 * The flow:
 *
 * proxy -> publish(Command)          -> pipeline -> device
 *                                    -> bed-level
 *
 * device -> publish(Response)        -> pages
 *                                    -> bed-level
 *                                    -> proxy
 *                                    -> delta
 *
 * device -> publish(ControlResponse) -> pages
 */
class PrintPipelineActor(proxy: ActorRef, device: ActorRef) extends Actor with ActorLogging {
  import PrintPipeline._

  val chunkManager = context.actorOf(Props[PageManagerActor], name="pages")
  val steps = context.actorOf(Props(classOf[StepProcessorActor], chunkManager, ConfigMaker.plannerConfig), name="steps")
  val physics = context.actorOf(Props(classOf[PhysicsProcessorActor], steps, ConfigMaker.plannerConfig), name="physics")
  val delta = context.actorOf(Props(classOf[DeltaProcessorActor], physics, false), name="delta")

  val settingsManager = context.actorOf(Props(classOf[SettingsManagerActor], delta), name="settings")

  val drainDeadline = 2.seconds.fromNow

  override val supervisorStrategy = SupervisorStrategy.stoppingStrategy

  var pausers = Set[ActorRef]()

  context.children foreach context.watch

  context.system.eventStream.subscribe(self, classOf[Command])

  //device ! SerialDeviceActor.ResetNumbering
  device ! DeviceRestart
  proxy ! DeviceRestart

  log.info("Pipeline started, draining for 2 seconds")

  // simulate normal restart message
  context.system.eventStream.publish(TextResponse("start"))

  checkPause()

  def checkPause(): Unit = {
    val msg = if (pausers.isEmpty) ResumeInput else PauseInput

    proxy ! msg
  }

  def receive = {
    case Terminated(_) =>
      sys.error("Child terminated, restarting pipeline")

    // input flow control
    case PauseInput =>
      pausers += sender
      checkPause()
    case ResumeInput =>
      pausers -= sender
      checkPause()

    // tail of pipeline
    case x: Command if sender == chunkManager => device.tell(x, sender)
    case x: LineSerial.Bytes if sender == chunkManager => device.tell(x, sender)

    // head of pipeline, from proxy
    case x: Command if drainDeadline.isOverdue() || !x.isGCommand =>
      delta.tell(x, sender)
    case x: Command => log.warning("Discarding command {}", x)
  }
}

object Server extends App {
  val classLoader = getClass.getClassLoader

  val system = ActorSystem("stepd", ConfigMaker.config, classLoader)

  sys.addShutdownHook {
    if (sys.env contains "STEPD_DUMP_CLASSES") {
      var classNames = Set[String]()

      ClassGetter foreach {
        case x: Class[_] => classNames += x.getName
        case _ =>
      }

      println(classNames.mkString("\n"))
    }

    Await.result(system.terminate(), 10.seconds)
  }

  //prevent AWT from opening a window
  System.setProperty("java.awt.headless", "true")

  preloadClasses()

  {
    val device = system.actorOf(Props(classOf[SerialDeviceActor], ConfigMaker.deviceConfig), name="device")
    val proxy = system.actorOf(Props(classOf[SocatProxy]), name="proxy")

    system.actorOf(Props(classOf[MeshLevelingActor], ConfigMaker.levelingConfig), name="bed-level")
    system.actorOf(Props(classOf[PrintPipelineActor], proxy, device), name="pipeline")
  }

  def preloadClasses(): Unit = {
    val lines = Source.fromResource("bootstrap-classes.txt").getLines

    var loaded = 0
    var failed = 0

    lines foreach { name =>
      try {
        classLoader.loadClass(name)
        loaded += 1
      } catch {
        case _: Throwable => failed += 1
      }
    }

    println(s"Preloaded $loaded classes, and $failed failed")
  }
}
