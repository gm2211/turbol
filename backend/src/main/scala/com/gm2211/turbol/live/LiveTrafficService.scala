/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.google.common.cache.{Cache, CacheBuilder}

import java.time.{Duration, Instant}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class TrafficSnapshot(aircraft: Seq[LiveAircraft], updated: Instant, complete: Boolean)

/**
 * Live aircraft for a map viewport. adsb.lol answers point/radius queries (max 250 nm), so the world is cut into a
 * fixed lattice of ~5 x 5 degree cells, each fetched as one 250 nm circle and cached briefly. Cells are fixed so
 * every viewer shares the cache; a wide viewport fetches at most [[LiveTrafficService.maxFetchesPerRequest]] stale
 * cells per call and fills in over the next refreshes, to stay polite to the free API.
 */
final class LiveTrafficService extends BackendLogging {
  import LiveTrafficService.*

  private val cells = new ConcurrentHashMap[Cell, (Instant, Seq[LiveAircraft])]()
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))
  private val routeCache: Cache[String, Option[FlightRoute]] =
    CacheBuilder.newBuilder().maximumSize(5000).expireAfterWrite(Duration.ofHours(6)).build()

  def route(callsign: String): Option[FlightRoute] =
    routeCache.get(callsign.toUpperCase, () => LiveTrafficClient.route(callsign))

  def aircraftIn(south: Double, west: Double, north: Double, east: Double): TrafficSnapshot = {
    val now = Instant.now()
    val wanted = cellsCovering(south, west, north, east)
    val centerLat = (south + north) / 2
    val centerLon = (west + east) / 2
    val stale = wanted
      .filter(c => Option(cells.get(c)).forall { case (at, _) => Duration.between(at, now).compareTo(cellTtl) > 0 })
      .sortBy(c => math.abs(c.lat - centerLat) + math.abs(c.lon - centerLon))
    val toFetch = stale.take(maxFetchesPerRequest)
    val fetched = toFetch.map { cell =>
      Future {
        Try(LiveTrafficClient.aircraftAround(cell.lat, cell.lon, cellRadiusNm)).fold(
          e => log.warn("adsb.lol fetch failed", safe("cell", cell.toString), unsafe("error", e.getMessage)),
          { case (_, aircraft) => cells.put(cell, (Instant.now(), aircraft)): Unit }
        )
      }
    }
    Try(Await.result(Future.sequence(fetched), 25.seconds)): Unit
    val all = wanted.flatMap(c => Option(cells.get(c))).flatMap(_._2)
    val inBox = all
      .filter(a => a.lat >= south && a.lat <= north && a.lon >= west && a.lon <= east)
      .groupBy(_.hex)
      .values
      .map(_.minBy(_.seenSecondsAgo))
      .toSeq
    val oldest = wanted.flatMap(c => Option(cells.get(c))).map(_._1).minOption.getOrElse(now)
    TrafficSnapshot(inBox, oldest, complete = stale.size <= maxFetchesPerRequest)
  }

  /** Forget cells nobody asked about recently, so memory stays bounded. */
  def evictOld(): Unit =
    cells.entrySet().asScala.filter(e => Duration.between(e.getValue._1, Instant.now()).toMinutes > 10).foreach { e =>
      cells.remove(e.getKey): Unit
    }
}

object LiveTrafficService {
  final case class Cell(lat: Double, lon: Double)

  private val cellDeg = 5.0
  val cellRadiusNm = 250 // adsb.lol's maximum
  val maxFetchesPerRequest = 12
  private val cellTtl = Duration.ofSeconds(30)
  private val maxCells = 120

  /** Lattice cell centres covering a bounding box; longitude spacing widens with latitude to keep cells ~square. */
  def cellsCovering(south: Double, west: Double, north: Double, east: Double): Seq[Cell] = {
    val rows = (math.floor(south / cellDeg).toInt to math.floor(north / cellDeg).toInt)
      .map(r => (r + 0.5) * cellDeg)
      .filter(lat => lat > -85 && lat < 85)
    val all = rows.flatMap { lat =>
      val lonStep = cellDeg / math.max(0.3, math.cos(math.toRadians(math.abs(lat) + cellDeg / 2)))
      val westCol = math.floor((math.max(west, -180) + 180) / lonStep).toInt
      val eastCol = math.floor((math.min(east, 180) + 180) / lonStep).toInt
      (westCol to eastCol).map(col => Cell(lat, (col + 0.5) * lonStep - 180))
    }
    all.take(maxCells)
  }
}
