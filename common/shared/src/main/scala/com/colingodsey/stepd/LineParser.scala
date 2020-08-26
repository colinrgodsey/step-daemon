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

import com.colingodsey.stepd.GCode._

trait LineParser {
  private val buffer = new Array[Char](1024)
  private var idx = 0
  private var lastN = 0

  def process(cmd: Raw): Unit

  def sendOk(n: Option[Int]): Unit

  def process(line: String): Unit = if(line.trim.nonEmpty) {
    val cmd0 = line.indexOf('*') match {
      case -1 => line
      case idx =>
        val cmd = line.substring(0, idx)
        val checksum = line.substring(idx + 1).toInt

        if(checksum != calcChecksum(cmd))
          sys.error("Failed checksum! Last N: " + lastN)

        cmd
    }
    val cmd = cmd0.trim

    if(cmd startsWith "N") {
      val idx = cmd.indexOf(' ')
      val n = cmd.drop(1).take(idx).trim.toInt

      lastN = n

      process(Raw(cmd.drop(idx + 1)))
      sendOk(Some(n))
    } else {
      val raw = Raw(cmd)

      process(raw)

      if(raw.cmd != "M105") sendOk(None)
    }
  }

  def process(char: Char): Unit = char match {
    case '\r' | '\n' if idx != 0 =>
      val line = buffer.iterator.take(idx).mkString.trim

      idx = 0

      //remove comments
      line.indexOf(';') match {
        case -1 => process(line)
        case 0 => //ignore
        case n => process(line take n)
      }
    case '\r' | '\n' => //ignore if start of line
    case _ if idx == buffer.length =>
      sys.error("Buffer overflow!")
    case c =>
      buffer(idx) = c
      idx += 1
  }

  def process(byte: Byte): Unit =
    process(byte.toChar)

  def calcChecksum(str: String): Int =
    str.iterator.map(_.toByte).foldLeft(0)(_ ^ _) & 0xFF
}
