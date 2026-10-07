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
