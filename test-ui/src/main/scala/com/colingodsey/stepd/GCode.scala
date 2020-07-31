package com.colingodsey.stepd

import com.colingodsey.stepd.Math.Vec4

object GCode {
  def apply(line: String): Command = {
    val raw = Raw(line)
    raw.cmd match {
      case "G0" | "G1" => GMove(raw)
      case "G6" => GDirectMove(raw)
      case "G28" => Home(raw)
      case "G29" => ZProbe
      case "G90" => SetAbsolute
      case "G91" => SetRelative
      case "G92" => SetPos(raw)

      case "M92" => SetStepsPerUnit(raw)
      case "M114" => GetPos
      case "M201" => SetMaxAcceleration(raw)
      case "M203" => SetMaximumFeedRate(raw)
      case "M220" => FeedRate(raw)
      case "M221" => FlowRate(raw)
      case "M503" => ReportSettings
      case "M900" => LinearAdvanceFactor(raw)

      case _ => raw
    }
  }

  object Command {
    def unapply(arg: Command): Option[String] =
      Some(arg.raw.cmd)
  }

  sealed trait Command {
    def raw: Raw
    def isGCommand: Boolean

    override def toString: String = raw.toString
  }
  sealed trait MCommand extends Command {
    def isGCommand = false
  }
  sealed trait GCommand extends Command {
    def isGCommand = true
  }

  case class Raw(line0: String) extends Command {
    private val split = {
      val line = if (line0.indexOf('*') != -1) {
        line0.take(line0.indexOf('*'))
      } else {
        line0
      }
      val spl = line.split(' ').toVector.filter(_.nonEmpty)
      if (spl(0)(0) == 'N') spl.drop(1) else spl
    }

    val cmd = split.head
    val parts = split.tail

    def raw = this

    def isGCommand = cmd(0) == 'G'

    def getPart(ident: Char): Option[String] =
      parts.filter(_.head == ident).headOption.map(_.tail)

    def hasPart(ident: Char) = getPart(ident).isDefined

    override def toString: String = line0
  }

  object GDirectMove {
    def tb(str: String): Boolean = str match {
      case "1" => true
      case _ => false
    }

    def apply(raw: Raw): GDirectMove = GDirectMove(
      raw.getPart('I').map(_.toInt),
      raw.getPart('R').map(_.toInt),
      raw.getPart('S').map(_.toInt),
      raw.getPart('X').map(tb),
      raw.getPart('Y').map(tb),
      raw.getPart('Z').map(tb),
      raw.getPart('E').map(tb)
    )
  }
  case class GDirectMove(index: Option[Int] = None,
                         speed: Option[Int] = None,
                         steps: Option[Int] = None,
                         xDir: Option[Boolean] = None,
                         yDir: Option[Boolean] = None,
                         zDir: Option[Boolean] = None,
                         eDir: Option[Boolean] = None) extends Command {

    def dirString(c: Char, value: Option[Boolean]) = value match {
      case Some(true) => s" ${c}1"
      case Some(false) => s" ${c}0"
      case _ => ""
    }

    val raw = Raw {
      "G6" +
        index.map(x => s" I$x").getOrElse("") +
        speed.map(x => s" R$x").getOrElse("") +
        steps.map(x => s" S$x").getOrElse("") +
        dirString('X', xDir) +
        dirString('Y', yDir) +
        dirString('Z', zDir) +
        dirString('E', eDir)
    }

    def isGCommand = false
  }

  object GMove {
    def apply(raw: Raw): GMove =
      GMove(
        raw.getPart('X').map(_.toDouble),
        raw.getPart('Y').map(_.toDouble),
        raw.getPart('Z').map(_.toDouble),
        raw.getPart('E').map(_.toDouble),
        raw.getPart('F').map(_.toDouble)
      )(raw)
  }

  case class GMove(x: Option[Double], y: Option[Double], z: Option[Double], e: Option[Double], f: Option[Double])(val raw: Raw) extends GCommand {
    def isFrOnly = x == None && y == None && z == None && e == None && f.isDefined
  }

  object SetPos {
    def apply(raw: Raw): SetPos =
      SetPos(
        raw.getPart('X').map(_.toDouble),
        raw.getPart('Y').map(_.toDouble),
        raw.getPart('Z').map(_.toDouble),
        raw.getPart('E').map(_.toDouble)
      )(raw)

    def apply(pos: Vec4): SetPos = {
      val line = s"G92 X${pos.x.toFloat} Y${pos.y.toFloat} Z${pos.z.toFloat} E${pos.e.toFloat}"

      SetPos(
        Some(pos.x),
        Some(pos.y),
        Some(pos.z),
        Some(pos.e)
      )(Raw(line))
    }
  }

  case class SetPos(x: Option[Double], y: Option[Double], z: Option[Double], e: Option[Double])(val raw: Raw) extends GCommand

  case object ZProbe extends GCommand {
    val raw = Raw("G29 V3 T")
  }

  case object SetAbsolute extends GCommand {
    val raw = Raw("G90")
  }

  case object SetRelative extends GCommand {
    val raw = Raw("G91")
  }

  object Home {
    def apply(raw: Raw): Home =
      Home(raw.hasPart('X'), raw.hasPart('Y'), raw.hasPart('Z'))

    val All = Home(true, true, true)
  }

  case class Home(homeX: Boolean, homeY: Boolean, homeZ: Boolean) extends GCommand {
    val homeAll = homeX && homeY && homeZ

    val raw = if(homeAll) Raw("G28") else Raw(homeSpecific)

    def homeSpecific = {
      val str = "G28 " +
          (if(homeX) "X " else "") +
          (if(homeY) "Y " else "") +
          (if(homeZ) "Z " else "")

      str.trim
    }
  }

  case object GetPos extends MCommand {
    val raw = Raw("M114")
  }

  case object ReportSettings extends MCommand {
    val raw = Raw("M503")
  }

  object FlowRate {
    def apply(raw: Raw): FlowRate =
      FlowRate(raw.getPart('S').map(_.toDouble))(raw)
  }

  case class FlowRate(perc: Option[Double])(val raw: Raw) extends MCommand

  object FeedRate {
    def apply(raw: Raw): FeedRate =
      FeedRate(raw.getPart('S').map(_.toDouble))(raw)
  }

  case class FeedRate(perc: Option[Double])(val raw: Raw) extends MCommand

  object LinearAdvanceFactor {
    def apply(raw: Raw): LinearAdvanceFactor =
      LinearAdvanceFactor(raw.getPart('K').map(_.toDouble))(raw)
  }

  case class LinearAdvanceFactor(k: Option[Double])(val raw: Raw) extends MCommand

  object SetStepsPerUnit {
    def apply(raw: Raw): SetStepsPerUnit =
      SetStepsPerUnit(
        raw.getPart('X').map(_.toDouble),
        raw.getPart('Y').map(_.toDouble),
        raw.getPart('Z').map(_.toDouble),
        raw.getPart('E').map(_.toDouble)
      )(raw)
  }

  case class SetStepsPerUnit(x: Option[Double], y: Option[Double],
    z: Option[Double], e: Option[Double])(val raw: Raw) extends MCommand

  object SetMaximumFeedRate {
    def apply(raw: Raw): SetMaximumFeedRate =
      SetMaximumFeedRate(
        raw.getPart('X').map(_.toDouble),
        raw.getPart('Y').map(_.toDouble),
        raw.getPart('Z').map(_.toDouble),
        raw.getPart('E').map(_.toDouble)
      )(raw)
  }

  case class SetMaximumFeedRate(x: Option[Double], y: Option[Double],
    z: Option[Double], e: Option[Double])(val raw: Raw) extends MCommand

  object SetMaxAcceleration {
    def apply(raw: Raw): SetMaxAcceleration =
      SetMaxAcceleration(
        raw.getPart('X').map(_.toDouble),
        raw.getPart('Y').map(_.toDouble),
        raw.getPart('Z').map(_.toDouble),
        raw.getPart('E').map(_.toDouble)
      )(raw)
  }

  case class SetMaxAcceleration(x: Option[Double], y: Option[Double],
    z: Option[Double], e: Option[Double])(val raw: Raw) extends MCommand
}
