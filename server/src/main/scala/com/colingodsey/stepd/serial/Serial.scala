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

package com.colingodsey.stepd.serial

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor._
import akka.util.ByteString

import com.colingodsey.stepd.planner.DeviceConfig

import jssc.{SerialPort, SerialPortList}

import scala.concurrent.blocking

object Serial {
  object Bytes {
    def apply(x: String): Bytes =
      Bytes(ByteString fromString x)
  }
  case class Bytes(data: ByteString)

  sealed trait FlowCommand
  case object PauseRead extends FlowCommand
  case object ResumeRead extends FlowCommand

  final val SerialReadSize = 512

  /*
  NOTE: JSSC doesnt give us a good way to do an interruptible blocking read.
  The async and "timeout" version of read requires polling checks with a
  100ns pause in between. Waste of CPU, and not reactive.

  This thread may get stuck open forever... not much we can do about it.
   */
  private class ReaderThread(port: SerialPort, self: ActorRef) extends Thread {
    val shouldRead = new AtomicBoolean(true)
    val shouldClose = new AtomicBoolean(false)

    setDaemon(true)
    setName("Serial.ReaderThread")

    def wake(): Unit = {
      shouldRead set true
      synchronized(notifyAll())
    }

    def pause(): Unit = {
      shouldRead set false
    }

    def close(): Unit = {
      shouldClose set true
      wake()
      interrupt()
    }

    //TODO: add notify for sleep?
    override def run(): Unit = {
      try {
        while (!shouldClose.get) if (shouldRead.get) {
          val firstByte = Option(port readBytes 1) getOrElse Array.empty

          val toRead = math.min(port.getInputBufferBytesCount, SerialReadSize)

          val remainingBytes = Option(port readBytes toRead) getOrElse Array.empty
          val data: ByteString = ByteString(firstByte) ++ ByteString(remainingBytes)

          self ! data
        } else synchronized(wait(10)) //wait 10ms or until notify
      } finally {
        self ! PoisonPill
      }
    }
  }

  class Reader(port: SerialPort) extends Actor with Stash with ActorLogging {
    private val thread = new ReaderThread(port, self)

    thread.start()

    def receive = {
      case bytes: ByteString =>
        context.parent ! Serial.Bytes(bytes)
      case ResumeRead =>
        log debug "resuming"
        thread.wake()
      case PauseRead =>
        log debug "pausing"
        thread.pause()
      case x =>
        log.warning("Unhandled type " + x.getClass)
        log.warning("Unhandled value " + x)
        log.warning("from " + sender)
    }

    override def postStop(): Unit = {
      super.postStop()

      thread.wake()
      thread.close()
    }
  }

  class Writer(port: SerialPort) extends Actor with ActorLogging {
    def receive = {
      case Serial.Bytes(dat) =>
        val wrote = blocking(port writeBytes dat.toArray)

        require(wrote, "failed write")
    }

    override def postStop(): Unit = {
      super.postStop()
      //blocking(port writeBytes ByteString("\r\nM112\r\n").toArray)
      blocking(port writeBytes ByteString("\r\nM108\r\n").toArray)
    }
  }
}

class Serial(cfg: DeviceConfig) extends Actor with ActorLogging {
  import Serial._

  val port = initPort

  val writer = context.actorOf(
    Props(classOf[Writer], port).withDispatcher("akka.io.pinned-dispatcher"),
    name = "writer")

  //reader is also responsible for closing port... if possible
  val reader = context.actorOf(Props(classOf[Reader], port), name = "reader")

  context watch reader
  context watch writer

  def initPort = {
    log.info {
      val ports = SerialPortList.getPortNames().toSeq

      ports.toString
    }

    val port = new SerialPort(cfg.dev)

    port.openPort()
    port.setParams(cfg.baud,
      SerialPort.DATABITS_8,
      SerialPort.STOPBITS_1,
      SerialPort.PARITY_NONE)

    require(port.isOpened, "failed to open port")

    port
  }

  //the exciting life of a proxy actor
  def receive = {
    case x: Serial.Bytes if sender == reader =>
      context.parent ! x
    case x: Serial.Bytes =>
      writer ! x
    case x: FlowCommand =>
      reader ! x
  }

  override def postStop(): Unit = {
    super.postStop()

    context stop reader
    context stop writer

    blocking(port.closePort())
  }
}