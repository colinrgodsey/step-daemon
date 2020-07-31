package com.colingodsey.stepd

import akka.actor._
import akka.util.ByteString

object LineSerial {
  val ControlChar = '!'
  val ControlLineLength = 4 + 1
}

case class TextResponse(str: String)
case class ControlResponse(index: Int, data: ByteString)

class LineSerial extends Actor with ActorLogging with Stash {
  import LineSerial._

  log.info("starting...")

  val dataBuffer = new Array[Byte](4096)
  var bufferIdx = 0

  var isControl = false
  var controlId = 0
  var controlDataLen = 0

  def controlLen = controlDataLen + 3

  def processStr(str: String): Unit = {
    log.debug("recv: {}", str)
    context.parent ! TextResponse(str)
  }

  def bufferByte(b: Byte): Unit = {
    dataBuffer(bufferIdx) = b
    bufferIdx += 1
  }

  def processControlByte(b: Byte): Unit = b.toChar match {
    case _ if bufferIdx == 0 =>
      controlId = b
      bufferByte(b)
    case _ if bufferIdx == 1 =>
      controlDataLen = b & 0xFF
      if (controlDataLen == 0) controlDataLen = 256
      bufferByte(b)
    case _ if bufferIdx < controlLen =>
      bufferByte(b)
      if (bufferIdx == controlLen) {
        log.debug(s"got page data of size $controlDataLen")
        val dat = ByteString(dataBuffer).drop(2).take(controlDataLen)
        require(dat.length == controlDataLen, "bad page data")
        context.parent ! ControlResponse(controlId, dat)
        isControl = false
        bufferIdx = 0
      }
    case _ =>
      sys.error(s"Failed parsing control line. id: $controlId idx: $bufferIdx len: $controlLen")
  }

  def processByte(b: Byte): Unit = b.toChar match {
    case _ if isControl => processControlByte(b)

    case '\r' | '\n' if bufferIdx == 0 => // skip
    case ControlChar if bufferIdx == 0 => // control char on new line
      isControl = true
    case '\r' | '\n' =>
      processStr(new String(dataBuffer, 0, bufferIdx, "UTF-8"))
      bufferIdx = 0

    case _ => bufferByte(b)
  }

  def receive = {
    case data: ByteString => data foreach processByte
  }
}
