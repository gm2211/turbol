/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import cats.effect.IO
import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.background.BackgroundJob

import java.nio.file.{Files, Path, Paths}
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.Comparator
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

object TurbulenceDataUpdater {

  /** GTG forecast levels we fetch: climb/descent coverage plus every 2,000 ft through the cruise band. */
  val forecastLevelsFt: Set[Int] =
    Set(3000, 6000, 10000, 15000, 20000, 25000, 30000, 33000, 35000, 37000, 39000, 41000)
  val forecastHours: Range = 1 to 18
  private val completeMarker = "COMPLETE"

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
 * Keeps [[TurbulenceStore]] fresh: a GTGN nowcast every 15 minutes and a GTG v4 forecast cycle every few hours, both
 * from NOMADS. Decoded layers are cached on disk, so a restart serves data immediately instead of re-downloading.
 */
final class TurbulenceDataUpdater(
  store: TurbulenceStore,
  dataDir: Path = TurbulenceDataUpdater.defaultDataDir,
  forecastRefreshEvery: Duration = Duration.ofHours(3)
) extends BackgroundJob
    with BackendLogging {
  import TurbulenceDataUpdater.*

  private val nowcastDir = dataDir.resolve("nowcast")
  private val forecastDir = dataDir.resolve("forecast")

  override def run(): IO[Unit] =
    IO.blocking(loadCached()) >>
      (IO.blocking(refreshNowcast()).both(IO.blocking(refreshForecast())) >> IO.sleep(
        3.minutes
      )).foreverM

  /** Load the newest complete nowcast and forecast left on disk by a previous run. */
  def loadCached(): Unit = {
    subdirs(nowcastDir)
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)
      .foreach { dir =>
        val valid = Instant.ofEpochSecond(dir.getFileName.toString.toLong)
        store.setNowcast(EdrFrame(FrameKind.Nowcast, valid, valid, mapLayers(dir)))
        log.info("Loaded cached GTGN nowcast", safe("valid", valid))
      }
    subdirs(forecastDir)
      .filter(d => Files.exists(d.resolve(completeMarker)))
      .maxByOption(_.getFileName.toString.toLong)
      .foreach { dir =>
        val issued = Instant.ofEpochSecond(dir.getFileName.toString.toLong)
        store.setForecast(loadForecastHours(dir, issued))
        log.info("Loaded cached GTG forecast", safe("issued", issued))
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
    subdirs(cycleDir).flatMap { hourDir =>
      hourDir.getFileName.toString.stripPrefix("f").toIntOption.map { hour =>
        EdrFrame(FrameKind.Forecast, issued.plus(hour.toLong, ChronoUnit.HOURS), issued, mapLayers(hourDir))
      }
    }.toVector

  def refreshNowcast(): Unit = {
    val currentValid = store.current.nowcast.map(_.validTime)
    val candidates = NomadsClient.latestGtgnFiles().filter(f => currentValid.forall(f.validTime.isAfter))
    // The newest file can still be uploading; fall back to the next one if it doesn't decode completely.
    candidates.iterator.map(file => Try(fetchNowcast(file)).toOption).collectFirst { case Some(frame) =>
      store.setNowcast(frame)
      log.info("Updated GTGN nowcast", safe("valid", frame.validTime), safe("levels", frame.levels.size))
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
      val layers = GribDecoder.decodeToLayers(tmp, dir)
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
      val layers = GribDecoder.decodeToLayers(tmp, hourDir, forecastLevelsFt)
      require(layers.size == forecastLevelsFt.size, s"F$hour: got ${layers.size} of ${forecastLevelsFt.size} levels")
      log.info("Fetched GTG forecast hour", safe("hour", hour), safe("messages", messages))
      EdrFrame(FrameKind.Forecast, cycle.initTime.plus(hour.toLong, ChronoUnit.HOURS), cycle.initTime, layers)
    } finally Files.deleteIfExists(tmp): Unit
  }
}
