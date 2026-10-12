/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import cats.effect.{Deferred, IO}
import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.background.BackgroundJob

import java.nio.file.{Files, Path, Paths}
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

object TurbulenceDataUpdater {

  /**
   * GTG forecast levels we fetch: climb/descent coverage plus every 1,000 ft from FL300 to FL450, GTG's native spacing
   * (the files hold 51 levels, 100 ft then 1,000 to 50,000 ft), so the forecast is as fine as the nowcast where jets
   * cruise. Each level is ~1.9 MB on disk per forecast hour.
   */
  val forecastLevelsFt: Set[Int] =
    Set(3000, 6000, 10000, 15000, 20000, 25000) ++ (30000 to 45000 by 1000)
  val forecastHours: Range = 1 to 18

  /**
   * GFS forecast hours fetched for the global layer. A cycle is complete about 5 hours after its init and replaced 6
   * hours later, so +3 h to +30 h covers from a couple of hours ago to beyond GTG's 18-hour horizon the whole time.
   */
  val globalForecastHours: Range = 3 to 30 by 3

  /**
   * Share of wall-clock time the global layer's decoding may use, and the pause between its forecast hours when a
   * cycle replaces one already served. Render's instance has a tenth of a CPU, and decoding flat out (a few CPU
   * seconds per hour) starved request handling; at half duty with pauses a cycle takes several minutes instead, which
   * is fine for data refreshed every 6 hours.
   */
  val globalCpuDuty = 0.5
  val globalPauseBetweenHours: Duration = Duration.ofSeconds(20)

  /** Longest the forecast and global layers wait for the nowcast's first check on a fresh start. */
  val nowcastHeadStart: FiniteDuration = 4.minutes

  /** What the updater keeps fresh; a store starts with all of them loading (see [[TurbulenceData.loading]]). */
  val sources: Set[String] = Set("nowcast", "forecast", "global")
  private val completeMarker = "COMPLETE"
  private val wafsMarker = "WAFS"

  def defaultDataDir: Path =
    sys.env.get("TURBOL_DATA_DIR").map(Paths.get(_)).getOrElse(Paths.get("var", "data", "turbulence"))

  private[turbulence] def deleteRecursively(path: Path): Unit =
    if (Files.exists(path)) {
      Files.walk(path).sorted(Comparator.reverseOrder()).iterator().asScala.foreach(p => Files.deleteIfExists(p): Unit)
    }

  private[turbulence] def subdirs(path: Path): Seq[Path] =
    if (!Files.isDirectory(path)) Seq.empty
    else Files.list(path).iterator().asScala.filter(Files.isDirectory(_)).toSeq
}

/**
 * Keeps [[TurbulenceStore]] fresh: a GTGN nowcast every 15 minutes (polled every minute) and a GTG v4 forecast cycle
 * every few hours, both from NOMADS. Decoded layers are cached on disk, so a restart serves data immediately instead of
 * re-downloading.
 */
final class TurbulenceDataUpdater(
  store: TurbulenceStore,
  dataDir: Path = TurbulenceDataUpdater.defaultDataDir,
  forecastRefreshEvery: Duration = Duration.ofHours(3),
  onNewFrames: Seq[EdrFrame] => Unit = _ => ()
) extends BackgroundJob
    with BackendLogging {
  import TurbulenceDataUpdater.*

  private val nowcastDir = dataDir.resolve("nowcast")
  private val forecastDir = dataDir.resolve("forecast")
  private val globalDir = dataDir.resolve("global")

  // One decode at a time across the three loops: each holds tens of MB of grids while it runs, and on a fresh start
  // all three decode for minutes on a fraction of a CPU, which together ran the heap out. Downloads still overlap.
  private val decoding = new Object

  // GTGN lands on NOMADS ~2-3 min after each quarter hour; a one-minute poll is two directory listings. The loops are
  // independent so a long forecast download (18 hours of GRIB) never holds back the next nowcast. GFS cycles land
  // every 6 hours, so the global layer checks every 10 minutes.
  //
  // On a fresh start (every deploy: the disk doesn't survive one) the nowcast goes first, since it's what the map opens
  // on: the other two wait for its first check (or a few minutes) rather than take turns with it on the CPU and the
  // decode lock, which left the map without turbulence for 4 minutes after a restart.
  override def run(): IO[Unit] =
    IO.blocking(loadCached()) >> Deferred[IO, Unit].flatMap { nowcastChecked =>
      IO.both(
        poll("nowcast", 1.minute)(refreshNowcast(), after = nowcastChecked.complete(()).void),
        IO.race(nowcastChecked.get, IO.sleep(nowcastHeadStart)) >>
          IO.both(poll("forecast", 3.minutes)(refreshForecast()), globalLoop)
      ).void
    }

  // TURBOL_GLOBAL_LAYER=off skips GFS downloads (e.g. on a small instance); "global" still counts as loaded so Flight
  // Packs don't wait for it.
  private val globalEnabled: Boolean = !sys.env.get("TURBOL_GLOBAL_LAYER").contains("off")

  private def globalLoop: IO[Nothing] =
    if (globalEnabled) poll("global", 10.minutes)(refreshGlobal())
    else IO(log.info("Global turbulence layer disabled by TURBOL_GLOBAL_LAYER=off")) >> IO(store.loaded("global")) >>
      IO.never

  // After its first check, found or failed, a source no longer holds back Flight Packs.
  private def poll(name: String, every: FiniteDuration)(refresh: => Unit, after: IO[Unit] = IO.unit): IO[Nothing] =
    (IO.blocking(refresh).handleErrorWith(e =>
      IO(log.warn("Turbulence refresh failed", safe("product", name), unsafe("error", e)))
    ) >> IO(store.loaded(name)) >> after >> IO.sleep(every)).foreverM

  private val cacheLoaded = AtomicBoolean(false)

  /**
   * Load the newest complete nowcast and forecast left on disk by a previous run (or handed over by it), once: the
   * launcher does it before the server starts answering, so the tiles a visitor opens on are already rendered.
   */
  def loadCached(): Unit = if (cacheLoaded.compareAndSet(false, true)) {
    subdirs(nowcastDir)
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)
      .foreach { dir =>
        val valid = Instant.ofEpochSecond(dir.getFileName.toString.toLong)
        val frame = EdrFrame(FrameKind.Nowcast, valid, valid, mapLayers(dir))
        store.setNowcast(frame)
        Try(onNewFrames(Seq(frame))): Unit
        log.info("Loaded cached GTGN nowcast", safe("valid", valid))
      }
    subdirs(forecastDir)
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)
      .foreach { dir =>
        val issued = Instant.ofEpochSecond(dir.getFileName.toString.toLong)
        val frames = loadForecastHours(dir, issued)
        store.setForecast(frames)
        Try(onNewFrames(frames)): Unit
        log.info("Loaded cached GTG forecast", safe("issued", issued))
      }
    subdirs(globalDir)
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)
      .foreach { dir =>
        val issued = Instant.ofEpochSecond(dir.getFileName.toString.toLong)
        val frames = loadHours(dir, issued, FrameKind.Global)
        store.setGlobal(frames, wafsIssued(dir))
        log.info("Loaded cached global turbulence layer", safe("issued", issued), safe("frames", frames.size))
      }
  }

  private def mapLayers(dir: Path): SortedMap[Int, EdrLayer] =
    SortedMap.from(
      Files
        .list(dir)
        .iterator()
        .asScala
        .filter(_.getFileName.toString.endsWith(".u8"))
        .map { file =>
          val level = file.getFileName.toString.stripSuffix(".u8").toInt
          level -> GribDecoder.mapLayer(file, level)
        }
    )

  private def loadForecastHours(cycleDir: Path, issued: Instant): Vector[EdrFrame] =
    loadHours(cycleDir, issued, FrameKind.Forecast)

  private def loadHours(cycleDir: Path, issued: Instant, kind: FrameKind): Vector[EdrFrame] =
    subdirs(cycleDir).flatMap { hourDir =>
      hourDir.getFileName.toString.stripPrefix("f").toIntOption.map { hour =>
        EdrFrame(kind, issued.plus(hour.toLong, ChronoUnit.HOURS), issued, mapLayers(hourDir))
      }
    }.toVector

  def refreshNowcast(): Unit = {
    val currentValid = store.current.nowcast.map(_.validTime)
    val candidates = NomadsClient.latestGtgnFiles().filter(f => currentValid.forall(f.validTime.isAfter))
    // The newest file can still be uploading; fall back to the next one if it doesn't decode completely.
    candidates.iterator.map(file => Try(fetchNowcast(file)).toOption).collectFirst { case Some(frame) =>
      store.setNowcast(frame)
      log.info("Updated GTGN nowcast", safe("valid", frame.validTime), safe("levels", frame.levels.size))
      Try(onNewFrames(Seq(frame))): Unit
      subdirs(nowcastDir)
        .filterNot(_.getFileName.toString == frame.validTime.getEpochSecond.toString)
        .foreach(deleteRecursively)
    }: Unit
  }

  private def fetchNowcast(file: GtgnFile): EdrFrame = {
    val dir = nowcastDir.resolve(file.validTime.getEpochSecond.toString)
    Files.createDirectories(nowcastDir)
    val tmp = Files.createTempFile(nowcastDir, "gtgn", ".grib2")
    try {
      Http.download(file.url, tmp): Unit
      val layers = decoding.synchronized(GribDecoder.decodeToLayers(tmp, dir))
      if (layers.size < 51) {
        deleteRecursively(dir)
        throw RuntimeException(s"incomplete GTGN file ${file.url}: ${layers.size} levels")
      }
      Files.writeString(dir.resolve(completeMarker), file.url): Unit
      EdrFrame(FrameKind.Nowcast, file.validTime, file.validTime, layers)
    } catch {
      case e: Throwable =>
        log.warn("Failed to fetch GTGN file", safe("url", file.url), unsafe("error", e.getMessage))
        throw e
    } finally Files.deleteIfExists(tmp): Unit
  }

  def refreshForecast(): Unit = {
    val current = store.current.forecastIssued
    NomadsClient.latestCompleteGtgCycle().foreach { cycle =>
      val stale = current.forall(issued => !cycle.initTime.isBefore(issued.plus(forecastRefreshEvery)))
      if (stale) {
        log.info("Fetching GTG forecast cycle", safe("init", cycle.initTime))
        val cycleDir = forecastDir.resolve(cycle.initTime.getEpochSecond.toString)
        val frames = forecastHours.map { hour =>
          val frame = fetchForecastHour(cycle, hour, cycleDir)
          // First run: publish hour by hour so analysis works before the whole cycle is in.
          if (current.isEmpty) store.setForecast(store.current.forecast.filter(_.issued == cycle.initTime) :+ frame)
          frame
        }.toVector
        Files.writeString(cycleDir.resolve(completeMarker), cycle.gribUrl(0)): Unit
        store.setForecast(frames)
        Try(onNewFrames(frames)): Unit
        log.info("Updated GTG forecast", safe("init", cycle.initTime), safe("hours", frames.size))
        subdirs(forecastDir).filterNot(_ == cycleDir).foreach(deleteRecursively)
      }
    }
  }

  private def fetchForecastHour(cycle: GtgCycle, hour: Int, cycleDir: Path): EdrFrame = {
    val hourDir = cycleDir.resolve(f"f$hour%02d")
    Files.createDirectories(cycleDir)
    val tmp = Files.createTempFile(cycleDir, "gtg", ".grib2")
    try {
      val messages = NomadsClient.fetchGtgLevels(cycle, hour, forecastLevelsFt, tmp)
      val layers = decoding.synchronized(GribDecoder.decodeToLayers(tmp, hourDir, forecastLevelsFt))
      require(layers.size == forecastLevelsFt.size, s"F$hour: got ${layers.size} of ${forecastLevelsFt.size} levels")
      log.info("Fetched GTG forecast hour", safe("hour", hour), safe("messages", messages))
      EdrFrame(FrameKind.Forecast, cycle.initTime.plus(hour.toLong, ChronoUnit.HOURS), cycle.initTime, layers)
    } finally Files.deleteIfExists(tmp): Unit
  }

  /** Fetch a new GFS cycle for the global layer once one is complete, replacing the previous cycle. */
  def refreshGlobal(): Unit = {
    val current = store.current.globalIssued
    NomadsClient.latestCompleteGfsCycle(globalForecastHours.last).foreach { cycle =>
      if (current.forall(_.isBefore(cycle.initTime))) {
        log.info("Fetching GFS cycle for the global turbulence layer", safe("init", cycle.initTime))
        val cycleDir = globalDir.resolve(cycle.initTime.getEpochSecond.toString)
        val frames = globalForecastHours.map { hour =>
          if (current.nonEmpty && hour != globalForecastHours.head) Thread.sleep(globalPauseBetweenHours.toMillis)
          val frame = fetchGlobalHour(cycle, hour, cycleDir)
          // First run: publish hour by hour so the map fills in before the whole cycle is in.
          if (current.isEmpty) store.setGlobal(store.current.global :+ frame, wafsIssued(cycleDir))
          frame
        }.toVector
        Files.writeString(cycleDir.resolve(completeMarker), cycle.dir): Unit
        store.setGlobal(frames, wafsIssued(cycleDir))
        Try(onNewFrames(frames)): Unit
        log.info("Updated global turbulence layer", safe("init", cycle.initTime), safe("frames", frames.size))
        subdirs(globalDir).filterNot(_ == cycleDir).foreach(deleteRecursively)
      }
    }
  }

  private def fetchGlobalHour(cycle: GfsCycle, hour: Int, cycleDir: Path): EdrFrame = {
    val hourDir = cycleDir.resolve(f"f$hour%02d")
    Files.createDirectories(cycleDir)
    val tmp = Files.createTempFile(cycleDir, "gfs", ".grib2")
    try {
      NomadsClient.fetchGfsHour(cycle, hour, GfsTurbulence.pressureLevelsHpa, tmp): Unit
      val valid = cycle.initTime.plus(hour.toLong, ChronoUnit.HOURS)
      // WAFS from AWC is blended in where available; without it the hour is GFS-only rather than missing.
      val (wafs, layers) = decoding.synchronized {
        val throttle = new Throttle(globalCpuDuty)
        val wafs = Try(Wafs.fetch(valid, cycle.initTime, throttle)).toOption.flatten
        (wafs, GfsTurbulence.decodeToLayers(tmp, hourDir, wafs.map(_._2), throttle))
      }
      wafs.foreach { case (wafsCycle, _) =>
        Files.writeString(hourDir.resolve(wafsMarker), wafsCycle.getEpochSecond.toString): Unit
      }
      log.info(
        "Fetched GFS hour for the global layer",
        safe("hour", hour),
        safe("levels", layers.size),
        safe("wafsCycle", wafs.map(_._1.toString).getOrElse("none"))
      )
      EdrFrame(FrameKind.Global, valid, cycle.initTime, layers)
    } finally Files.deleteIfExists(tmp): Unit
  }

  /** Newest WAFS cycle blended into any hour of a global cycle directory. */
  private def wafsIssued(cycleDir: Path): Option[Instant] =
    subdirs(cycleDir)
      .flatMap(d => Try(Files.readString(d.resolve(wafsMarker)).trim.toLong).toOption)
      .maxOption
      .map(Instant.ofEpochSecond)
}
