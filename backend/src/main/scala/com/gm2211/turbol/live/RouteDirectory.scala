/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.{Http, TurbulenceDataUpdater}
import com.google.common.cache.{Cache, CacheBuilder}

import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.{Duration, Instant}
import java.util.zip.GZIPInputStream
import scala.util.Using

/**
 * Which callsigns fly an airport pair: the Virtual Radar Server standing-data route list (callsign -> airports in
 * order, e.g. `UAE206,...,KJFK-LIMC-OMDB`), as mirrored by adsb.lol. The 4.5 MB file is kept on disk and scanned per
 * new pair rather than indexed in memory (it has ~600k routes).
 */
final class RouteDirectory(dir: Path = TurbulenceDataUpdater.defaultDataDir.resolve("routes"))
    extends BackendLogging {
  private val url = "https://vrs-standing-data.adsb.lol/routes.csv.gz"
  private val file = dir.resolve("routes.csv.gz")
  private val maxAge = Duration.ofDays(7)
  private val pairs: Cache[String, Seq[String]] =
    CacheBuilder.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofHours(12)).build()

  private def ensureFile(): Unit = synchronized {
    val fresh =
      Files.exists(file) && Files.getLastModifiedTime(file).toInstant.isAfter(Instant.now().minus(maxAge))
    if (!fresh) {
      Files.createDirectories(dir)
      val tmp = Files.createTempFile(dir, "routes", ".tmp")
      try {
        Http.download(url, tmp, attempts = 2)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        log.info("Downloaded route directory", safe("bytes", Files.size(file)))
      } finally Files.deleteIfExists(tmp): Unit
    }
  }

  /** Callsigns whose route has `fromIcao` immediately followed by `toIcao` (one leg of a possibly multi-leg route). */
  def callsigns(fromIcao: String, toIcao: String): Seq[String] = {
    val leg = s"${fromIcao.toUpperCase}-${toIcao.toUpperCase}"
    pairs.get(
      leg,
      () => {
        ensureFile()
        Using.resource(
          new BufferedReader(
            new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)
          )
        ) { reader =>
          Iterator
            .continually(reader.readLine())
            .takeWhile(_ != null)
            .filter(_.contains(leg))
            .flatMap(RouteDirectory.parse)
            .collect { case (callsign, airports) if RouteDirectory.hasLeg(airports, fromIcao, toIcao) => callsign }
            .toVector
            .distinct
        }
      }
    )
  }
}

object RouteDirectory {
  /** `Callsign,Code,Number,AirlineCode,AirportCodes` -> (callsign, airports in order). */
  def parse(line: String): Option[(String, Seq[String])] =
    line.split(',') match {
      case Array(callsign, _, _, _, airports) if callsign.nonEmpty =>
        Some(callsign.trim.toUpperCase -> airports.split('-').toSeq)
      case _ => None
    }

  def hasLeg(airports: Seq[String], from: String, to: String): Boolean =
    airports.sliding(2).exists {
      case Seq(a, b) => a.equalsIgnoreCase(from) && b.equalsIgnoreCase(to)
      case _ => false
    }
}
