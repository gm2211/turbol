/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.io.{BufferedOutputStream, FileOutputStream}
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}
import scala.util.{Try, Using}

/** A GTGN nowcast file on NOMADS. */
final case class GtgnFile(url: String, validTime: Instant)

/** A complete GTG v4 forecast cycle on NOMADS (directory plus init hour). */
final case class GtgCycle(dayUrl: String, cycleHour: String, initTime: Instant) {
  def gribUrl(forecastHour: Int): String = f"${dayUrl}dafs.t${cycleHour}z.gtg.3km.conus.f$forecastHour%03d.grib2"
}

/** A GFS cycle on NOMADS (0.25 degree pressure-level files, `gfs.YYYYMMDD/HH/atmos/`). */
final case class GfsCycle(day: LocalDate, cycleHour: String) {
  val initTime: Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC).plus(cycleHour.toLong, ChronoUnit.HOURS)
  def fileName(forecastHour: Int): String = f"gfs.t${cycleHour}z.pgrb2.0p25.f$forecastHour%03d"
  def dir: String = s"/gfs.${day.format(DateTimeFormatter.BASIC_ISO_DATE)}/$cycleHour/atmos"
}

/**
 * Finds and fetches NOAA turbulence products on NOMADS (layout verified in data-pipeline/: GTGN every 15 minutes
 * under gtgn/prod/gtgn.YYYYMMDD/HH/, GTG forecast hourly under dafs/prod/dafs.YYYYMMDD/).
 */
object NomadsClient {
  private val base = "https://nomads.ncep.noaa.gov/pub/data/nccf/com"
  private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd")

  private def days(now: Instant): Seq[LocalDate] = {
    val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
    Seq(today, today.minusDays(1))
  }

  /**
   * Most recent GFS cycle whose `lastHour` forecast file is published (GFS writes forecast hours in order, so the
   * earlier ones are there too). A cycle finishes about 4-5 hours after its init time.
   */
  def latestCompleteGfsCycle(lastHour: Int, now: Instant = Instant.now()): Option[GfsCycle] = {
    val hourRegex = """href="(\d{2})/"""".r
    days(now).iterator.flatMap { day =>
      val dayUrl = s"$base/gfs/prod/gfs.${day.format(dayFormat)}/"
      val hours = Try(Http.getString(dayUrl)).toOption.toSeq
        .flatMap(listing => hourRegex.findAllMatchIn(listing).map(_.group(1)).toSeq)
        .distinct
        .sorted(using Ordering[String].reverse)
      hours.iterator.map(GfsCycle(day, _)).filter { cycle =>
        Try(Http.getString(s"$base/gfs/prod${cycle.dir}/${cycle.fileName(lastHour)}.idx", attempts = 1)).isSuccess
      }
    }.nextOption()
  }

  /**
   * Fetch UGRD, VGRD and HGT on `levelsHpa` for one GFS forecast hour through the NOMADS GRIB filter, which cuts the
   * ~500 MB file down to the 39 global fields (~27 MB) in one request.
   */
  def fetchGfsHour(cycle: GfsCycle, forecastHour: Int, levelsHpa: Seq[Int], destination: Path): Path = {
    val query = Seq("dir" -> cycle.dir, "file" -> cycle.fileName(forecastHour)) ++
      Seq("UGRD", "VGRD", "HGT").map(v => s"var_$v" -> "on") ++
      levelsHpa.map(p => s"lev_${p}_mb" -> "on")
    val url = s"https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl?" +
      query.map { case (k, v) => s"$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("&")
    Http.download(url, destination): Unit
    // The filter answers 200 with an HTML error page when the file isn't there.
    val magic = Using.resource(java.nio.file.Files.newInputStream(destination))(_.readNBytes(4))
    if (new String(magic, "US-ASCII") != "GRIB") throw RuntimeException(s"GFS filter returned no GRIB for $url")
    destination
  }

  /** Newest GTGN files first (up to `limit`), searching today and yesterday (UTC). */
  def latestGtgnFiles(now: Instant = Instant.now(), limit: Int = 4): Seq[GtgnFile] = {
    val fileRegex = """href="gtgn\.t(\d{2})(\d{2})z\.3km\.grib2"""".r
    val hourRegex = """href="(\d{2})/"""".r
    days(now).iterator
      .flatMap { day =>
        val dayUrl = s"$base/gtgn/prod/gtgn.${day.format(dayFormat)}/"
        val hours = Try(Http.getString(dayUrl)).toOption.toSeq
          .flatMap(listing => hourRegex.findAllMatchIn(listing).map(_.group(1)).toSeq)
          .distinct
          .sorted(using Ordering[String].reverse)
        hours.iterator.flatMap { hour =>
          val listing = Try(Http.getString(s"$dayUrl$hour/")).getOrElse("")
          fileRegex
            .findAllMatchIn(listing)
            .map { m =>
              val valid = day.atTime(m.group(1).toInt, m.group(2).toInt).toInstant(ZoneOffset.UTC)
              GtgnFile(s"$dayUrl$hour/gtgn.t${m.group(1)}${m.group(2)}z.3km.grib2", valid)
            }
            .toSeq
            .sortBy(_.validTime)(using Ordering[Instant].reverse)
        }
      }
      .take(limit)
      .toSeq
  }

  /** Most recent GTG cycle whose last forecast hour (F018) is published, i.e. a complete cycle. */
  def latestCompleteGtgCycle(now: Instant = Instant.now()): Option[GtgCycle] = {
    val cycleRegex = """href="dafs\.t(\d{2})z\.gtg\.3km\.conus\.f018\.grib2"""".r
    days(now).iterator.flatMap { day =>
      val dayUrl = s"$base/dafs/prod/dafs.${day.format(dayFormat)}/"
      val listing = Try(Http.getString(dayUrl)).getOrElse("")
      cycleRegex
        .findAllMatchIn(listing)
        .map(_.group(1))
        .toSeq
        .distinct
        .sorted(using Ordering[String].reverse)
        .map(hh => GtgCycle(dayUrl, hh, day.atStartOfDay().toInstant(ZoneOffset.UTC).plus(hh.toLong, ChronoUnit.HOURS)))
    }.nextOption()
  }

  /**
   * Fetch only the EDPARM messages at `levelsFt` from one forecast hour using HTTP Range requests against the `.idx`
   * (a full forecast-hour file is ~155 MB; a dozen levels is a few MB). Writes them back-to-back into `destination`.
   */
  def fetchGtgLevels(cycle: GtgCycle, forecastHour: Int, levelsFt: Set[Int], destination: Path): Int = {
    val url = cycle.gribUrl(forecastHour)
    val idx = Http.getString(url + ".idx")
    // Lines look like "153:81234567:d=2026100616:EDPARM:10668 m above mean sea level:1 hour fcst:"
    val rows = idx.linesIterator.flatMap { line =>
      line.split(':') match {
        case Array(num, offset, _, param, level, _*) => Some((num.toInt, offset.toLong, param, level))
        case _ => None
      }
    }.toVector
    val levelRegex = """(\d+) m above mean sea level""".r
    val wanted = rows.zipWithIndex.flatMap { case ((_, offset, param, level), k) =>
      level match {
        case levelRegex(metres)
            if param == "EDPARM" && levelsFt.contains(GribDecoder.metresToLevelFt(metres.toDouble)) =>
          rows.lift(k + 1).map(next => (offset, next._2 - 1))
        case _ => None
      }
    }
    Using.resource(new BufferedOutputStream(new FileOutputStream(destination.toFile))) { out =>
      wanted.foreach { case (start, end) =>
        out.write(Http.getBytes(url, Some((start, end))))
        Thread.sleep(300) // stay well under NOMADS' ~120 requests/minute limit
      }
    }
    wanted.size
  }
}
