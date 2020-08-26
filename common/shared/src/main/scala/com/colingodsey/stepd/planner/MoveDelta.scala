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

import com.colingodsey.stepd.Math.Vec4

object MoveDelta {
  val Empty = MoveDelta(Vec4.Zero, Vec4.Zero, 0)
}

final case class MoveDelta(from: Vec4, to: Vec4, f: Double) {
  val d = to - from
  val time = (d.length / f)
  val v = d / time

  val isValid = d.length > 0
  val isPrintMove = d.e > 0 && !isEOrZOnly

  def length = d.length

  def isEOrZOnly = d.x == 0 && d.y == 0

  def scaleFr(scale: Double) = copy(f = f * scale)
}