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
import com.colingodsey.stepd.GCode._
import com.colingodsey.stepd.PrintPipeline.{ControlResponse, PauseInput, Response, ResumeInput, TextResponse}
import com.colingodsey.stepd.planner._
import com.colingodsey.stepd.serial.{LineSerial, SerialDeviceActor}

import scala.collection.mutable
import scala.concurrent.duration._

object PageManagerActor {
  val MaxGCodeQueue = 4
  val NumPages = 16
  val MaxStash = 8

  val TestFailure = false

  case class StagedChunk(page: Int)

  case object HealthCheck
  case object Stats

  object PageState {
    case object Free extends PageState(0)
    case object Writing extends PageState(1)
    case object Ok extends PageState(2)
    case object Fail extends PageState(3)

    val All = Set[PageState](Free, Writing, Ok, Fail)

    //allowed transitions for client-provided state
    val TransitionMap = Map[PageState, Set[PageState]](
      Free -> Set(Free),
      // concurrency may produce false "free" when we start writing
      Writing -> Set(Ok, Fail, Writing),
      Ok -> Set(Ok, Free),
      Fail -> Set(Fail, Free)
    )

    val ValidTransitions: Set[(PageState, PageState)] = for {
      (from, tos) <- TransitionMap.toSet
      to <- tos
    } yield from -> to

    val byId: Int => PageState = All.map(x => x.id -> x).toMap

    def isValidTransition(x: (PageState, PageState)): Boolean = ValidTransitions(x)
  }
  sealed abstract class PageState(val id: Int)
}

class PageManagerActor extends Actor with ActorLogging with Timers {
  import PageManagerActor._
  import context.dispatcher

  //Queue items that may depend on a page being written
  val pending = mutable.Queue[Any]()

  val stash = mutable.Queue[Any]()

  //This is the host-authority page state
  var pageStates = Map[Int, PageState]().withDefaultValue(PageState.Free)
  var pageChunks = Map[Int, Page]()
  var lastSent = Deadline.now

  var pendingCommands = 0
  var sentSteps = 0
  var nextFreePage = 0
  var hasStarted = false
  var lastStats = Deadline.now

  var lastReportedSpeed = 0
  var lastReportedDirection = Seq(false, false, false, false)
  var hasReportedDirection = false

  def pageAvailable = pageStates(nextFreePage) == PageState.Free

  def waitingCommands = pendingCommands >= MaxGCodeQueue

  def shouldStashCommands = !pageAvailable || waitingCommands || !hasStarted

  // the queue is blocked waiting on a needed chunk transfer
  def canTransmitNext = pending.headOption match {
    case Some(StagedChunk(page)) => pageStates(page) == PageState.Ok
    case Some(_: Command) => !waitingCommands
    case Some(_) => sys.error("something random in our queue")
    case None => false
  }

  def writePage(page: Int) = {
    require(pageStates(page) != PageState.Writing)

    // set write flag eagerly as we can't transition back to free
    pageStates += page -> PageState.Writing

    log.debug("writing page {}", page)

    val testCorrupt = TestFailure && math.random() < 0.05

    val chunk = pageChunks(page)

    sentSteps += chunk.meta.steps getOrElse 1024

    context.parent ! LineSerial.Bytes(chunk.produceBytes(page, testCorrupt))
  }

  def addPage(x: Page) = {
    val page = nextFreePage

    nextFreePage += 1
    if (nextFreePage >= NumPages) nextFreePage = 0

    require(!pageChunks.contains(page), s"trying to replace pending page $page")

    pending += StagedChunk(page)
    pageChunks += page -> x
    writePage(page)
  }

  def sendGCodeCommand(cmd: Command): Unit = {
    context.parent ! cmd

    pendingCommands += 1
    lastSent = Deadline.now
  }

  def getDirectionBit(axis: Int, meta: StepProcessor.ChunkMeta) =
    if (lastReportedDirection(axis) != meta.directions(axis) || !hasReportedDirection)
      Some(meta.directions(axis))
    else None

  def drainPending(): Unit = while (canTransmitNext) pending.removeHead() match {
    case StagedChunk(page) =>
      val chunk = pageChunks(page)

      val speed = if (lastReportedSpeed != chunk.meta.speed) {
        lastReportedSpeed = chunk.meta.speed
        Some(lastReportedSpeed)
      } else None

      val xDir = getDirectionBit(0, chunk.meta)
      val yDir = getDirectionBit(1, chunk.meta)
      val zDir = getDirectionBit(2, chunk.meta)
      val eDir = getDirectionBit(3, chunk.meta)

      hasReportedDirection = true
      lastReportedDirection = chunk.meta.directions

      val move = GDirectMove(
        index=Some(page),
        speed=speed,
        steps=chunk.meta.steps,
        xDir=xDir,
        yDir=yDir,
        zDir=zDir,
        eDir=eDir
      )

      log.debug("G6 for {}", page)
      sendGCodeCommand(move)
    case x: Command =>
      sendGCodeCommand(x)
    case x => sys.error("unexpected queued item " + x)
  }

  def drainStash(): Unit =
    while (!shouldStashCommands && stash.nonEmpty)
      receiveInput(stash.removeHead())

  def drainAll(): Unit = {
    drainPending()
    drainStash()
    doPauseCheck()
  }

  def doPauseCheck(): Unit = {
    val msg = if (stash.length < MaxStash) ResumeInput else PauseInput

    context.parent ! msg
  }

  def sendPageUnlock(page: Int) = {
    val bytes = Page.getUnlockPageBytes(page)
    context.parent ! LineSerial.Bytes(bytes)
  }

  def updatePageState(page: Int, state: PageState): Unit = {
    val curState = pageStates(page)
    val trans = curState -> state

    if (PageState isValidTransition trans) {
      pageStates += page -> state

      if (curState != state)
        log.debug("page {} transitioned from {} to {}", page, curState, state)

      if (trans == (PageState.Ok, PageState.Free))
        pageChunks -= page

      if (trans == (PageState.Fail, PageState.Free)) {
        log.debug("Failed page {} unlocked", page)
        writePage(page)
      }

      if (trans == (PageState.Writing, PageState.Fail)) {
        log.warning("Page {} failed to write", page)
        sendPageUnlock(page)
      }
    } else if (trans != (PageState.Writing, PageState.Free)) {
      log.warning("Bad state transition for {}: {}", page, trans)
    }
  }

  def updatePageState(bytes: ByteString): Unit = {
    require(bytes.length == 5)

    val testCorruption = if (TestFailure && math.random() < 0.05) 1 else 0

    val checksum = bytes(4)
    val crc = (bytes.slice(0, 4).foldLeft(0)(_ ^ _) & 0xFF).toByte + testCorruption.toByte

    if (crc != checksum) {
      log.warning("Failed checksum on control message: expected {} got {}", checksum, crc)
    } else for {
      pageIdx <- 0 until 16
      byteIdx = pageIdx / 4
      bitIdx = (pageIdx * 2) % 8
      stateId = (bytes(byteIdx) >>> bitIdx) & 3
      state = PageState byId stateId
    } updatePageState(pageIdx, state)
  }

  def receiveInput: PartialFunction[Any, Unit] = {
    case x @ (_: Page | _: Command) if shouldStashCommands =>
      stash += x

    case x: Page =>
      addPage(x)

    case x: Command =>
      pending += x
  }

  def receive = {
    case TextResponse(str) if str.startsWith("pages_ready") && !hasStarted =>
      hasStarted = true

      timers.startTimerAtFixedRate("health", HealthCheck, 1.second)
      timers.startTimerAtFixedRate("stats", Stats, 5.seconds)

      log.info("starting " + self.path.toStringWithoutAddress)

      for (i <- 0 until NumPages) pageStates += i -> PageState.Free

      doPauseCheck()
    case TextResponse(str) if str.contains("echo:start") && hasStarted =>
      log.error("Printer restarted!")
      context stop self
    case TextResponse(_) =>

    case ControlResponse(bytes) =>
      updatePageState(bytes)
      drainAll()

    case _: StepProcessor.SyncPos =>

    case _: GDirectMove =>
      sys.error("Received a G6 code!")

    case x: Page =>
      receiveInput(x)
      drainAll()
    case x: Command =>
      receiveInput(x)
      drainAll()

    case PrintPipeline.Completed(cmd) =>
      require(pendingCommands > 0, "more oks than sent commands!")
      pendingCommands -= 1

      log.debug("ok: {}", cmd)

      drainAll()

    case HealthCheck if shouldStashCommands =>
      //TODO: this fails when waiting on heating. hrm....
      /*if ((Deadline.now - lastSent) > 2.seconds) {
        log.error(s"Pipeline has stalled out! " + stateString)
        context.parent ! PoisonPill
      }*/
    case HealthCheck =>

    case Stats =>
      val now = Deadline.now
      val d = now - lastStats
      val stepsPerSec = (sentSteps * 1000 / d.toMillis).toInt

      sentSteps = 0
      lastStats = now

      if (stepsPerSec > 0) {
        log.info("steps/s: {}", stepsPerSec)
      }
  }

  def stateString = {
    def pendingPageId = pending.headOption match {
      case Some(StagedChunk(x)) => Some(x)
      case _ => None
    }

    s"pageAvailable: $pageAvailable waitingCommands: $waitingCommands\n" +
      s"queueSize: ${pending.size} pendingPageId: $pendingPageId\n" +
      s"pageStates: $pageStates"
  }

  override def postStop(): Unit = {
    log.info("Stopping: " + stateString)
    super.postStop()
  }

  override def preStart(): Unit = {
    super.preStart()

    context.system.eventStream.subscribe(self, classOf[Response])
  }
}
