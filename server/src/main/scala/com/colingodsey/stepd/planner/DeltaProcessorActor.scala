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

import com.colingodsey.stepd.GCode._
import akka.actor._
import com.colingodsey.stepd.Math.Vec4
import com.colingodsey.stepd.PrintPipeline.{PauseInput, ResumeInput, TextResponse}
import com.colingodsey.stepd.serial.LineSerial

import scala.concurrent.duration._

class DeltaProcessorActor(val next: ActorRef, ignoreM114: Boolean) extends DeltaProcessor
    with Actor with ActorLogging with Stash {
  import context.dispatcher

  var deltasProcessed: Int = 0

  var isAbsolute = true
  var frScale = 1.0

  //TODO: need some regular scheduled check to see if if we need to send down an empty delta.
  //is helpful for single or small sets of commands that dont do anything after

  //TODO: maybe need a timeout here?
  def waitingM114: Receive = {
    case TextResponse(str) if str.startsWith("X:") && str.contains(" Count ") =>
      //Recv: X:0.00 Y:0.00 Z:10.00 E:0.00 Count X:0 Y:0 Z:16000
      val parts = str.trim.split(' ')

      val x = parts(0).drop(2).toFloat
      val y = parts(1).drop(2).toFloat
      val z = parts(2).drop(2).toFloat
      val e = parts(3).drop(2).toFloat

      pos = Vec4(x, y, z, e)

      log.info("Synced new position from device: " + pos)

      next ! SetPos(pos)
      context.parent ! ResumeInput

      unstashAll()
      context become receive
    case _: TextResponse =>

    case _ => stash()
  }

  def receive: Receive = {
    case GetPos if !ignoreM114 =>
      //new behavior first
      context become waitingM114

      log info "syncing pipeline position"

      next ! GetPos
      context.parent ! PauseInput
    case x: SetPos =>
      process(x)
      //sendDown(getSetPos)
      next ! x
    case x: GMove =>
      process(x)

    case SetAbsolute =>
      next ! SetAbsolute

      log info "setting to absolute coords"

      isAbsolute = true
    case SetRelative =>
      next ! SetRelative

      log info "setting to relative coords"

      isAbsolute = false

    case a @ FeedRate(Some(x)) =>
      next ! a

      frScale = x / 100.0
      log info s"setting feed rate scale to $frScale"

    case x: Command =>
      //sendDown(getSetPos)
      next ! x

    case _: TextResponse =>
  }

  def process(delta: MoveDelta): Unit = {
    next ! delta

    deltasProcessed += 1
  }

  override def preStart(): Unit = {
    super.preStart()

    context.system.eventStream.subscribe(self, classOf[TextResponse])
  }
}
