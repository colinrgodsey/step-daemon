package com.colingodsey.stepd

object PageFormat {
  case object SP_4x4D_128 extends PageFormat {
    val Directional = true
    val BytesPerChunk = 256
    val BytesPerSegment = 2
    val StepsPerSegment = 7

    val SegmentsPerChunk = BytesPerChunk / BytesPerSegment
    val StepsPerChunk = SegmentsPerChunk * StepsPerSegment
  }

  case object SP_4x2_256 extends PageFormat {
    val Directional = false
    val BytesPerChunk = 256
    val StepsPerSegment = 3

    val StepsPerChunk = 256 * StepsPerSegment
  }

  case object SP_4x1_512 extends PageFormat {
    val Directional = false
    val BytesPerChunk = 256
    val StepsPerSegment = 1

    val StepsPerChunk = 512 * StepsPerSegment
  }
}
trait PageFormat {
  val BytesPerChunk: Int
  val StepsPerSegment: Int
  val StepsPerChunk: Int
  val Directional: Boolean
}
