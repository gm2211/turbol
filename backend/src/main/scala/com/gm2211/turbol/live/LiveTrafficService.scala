/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.RouteAnalyzer
import com.google.common.cache.{Cache, CacheBuilder}

import java.time.{Duration, Instant}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.concurrent.duration.DurationLong
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** One lattice cell's aircraft as last fetched from adsb.lol. */
final case class CellSnapshot(cell: LiveTrafficService.Cell, fetchedAt: Instant, aircraft: Seq[LiveAircraft])
final case class TrafficCells(cells: Seq[CellSnapshot], complete: Boolean)

/**
 * Live aircraft for a map viewport. adsb.lol answers point/radius queries (max 250 nm), so the world is cut into a
 * fixed lattice of ~5 x 5 degree cells, each fetched as one 250 nm circle and cached briefly. Cells are fixed so
 * every viewer shares the cache. adsb.lol allows ~24 requests a minute, so stale cells are refreshed in the
 * background (oldest first) and a whole-CONUS view fills in over a few minutes; zoomed-in views stay fresh. Requests
 * never wait for adsb.lol: they get whatever is cached and poll again.
 */
final class LiveTrafficService extends BackendLogging {
  import LiveTrafficService.*

  private val cells = new ConcurrentHashMap[Cell, (Instant, Seq[LiveAircraft])]()
  private val inFlight = ConcurrentHashMap.newKeySet[Cell]()
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))
  private val routeCache: Cache[String, Option[FlightRoute]] =
    CacheBuilder.newBuilder().maximumSize(5000).expireAfterWrite(Duration.ofHours(6)).build()

  def route(callsign: String): Option[FlightRoute] =
    routeCache.get(callsign.toUpperCase, () => LiveTrafficClient.route(callsign))

  /**
   * The aircraft as the cached cells last saw it, dead-reckoned to now, if one saw it within `maxAge`. Following a
   * flight picked on the map starts from this instead of asking adsb.lol, which took 2-18 s to answer one hex.
   */
  def cached(hex: String, maxAge: Duration = cachedMaxAge, now: Instant = Instant.now()): Option[LiveAircraft] =
    cells
      .values()
      .iterator()
      .asScala
      .flatMap { case (at, aircraft) =>
        aircraft.iterator.filter(_.hex.equalsIgnoreCase(hex)).map(a =>
          (a, Duration.between(at, now).toMillis / 1000.0 + a.seenSecondsAgo)
        )
      }
      .filter(_._2 <= maxAge.toSeconds)
      .minByOption(_._2)
      .map { case (a, age) => extrapolate(a, age).copy(seenSecondsAgo = age) }

  private val alongPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  /**
   * Every aircraft within reach of the great circle between two points: the lattice cells along it, from the cache
   * when fresh and fetched now otherwise (a person is waiting on a route search, so these are foreground calls).
   */
  def aircraftAlong(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Seq[LiveAircraft] = {
    val steps = math.max(1, math.ceil(RouteAnalyzer.greatCircleKm(fromLat, fromLon, toLat, toLon) / alongStepKm).toInt)
    // Each step re-aims at the destination, so the points stay on the great circle.
    val points = Iterator
      .iterate((fromLat, fromLon)) { case (lat, lon) =>
        val step = math.min(alongStepKm, RouteAnalyzer.greatCircleKm(lat, lon, toLat, toLon))
        RouteAnalyzer.destination(lat, lon, RouteAnalyzer.bearing(lat, lon, toLat, toLon), step)
      }
      .take(steps + 1)
      .toSeq
    val wanted = points.flatMap { case (la, lo) => cellsCovering(la, lo, la, lo) }.distinct
    val now = Instant.now()
    def cached(cell: Cell): Seq[LiveAircraft] = Option(cells.get(cell)).fold(Seq.empty)(_._2)
    val fetched = wanted.map { cell =>
      Option(cells.get(cell)).filter(c => Duration.between(c._1, now).compareTo(alongMaxAge) <= 0) match {
        case Some((_, aircraft)) => Future.successful(aircraft)
        case None =>
          Future {
            Try(LiveTrafficClient.aircraftAround(cell.lat, cell.lon, cellRadiusNm, background = false)).fold(
              e => {
                log.warn("adsb.lol fetch failed", safe("cell", cell.toString), unsafe("error", e.getMessage))
                cached(cell)
              },
              { case (_, aircraft) => cells.put(cell, (Instant.now(), aircraft)); aircraft }
            )
          }(using alongPool)
      }
    }
    // A long route can outrun adsb.lol's rate limit: answer with what arrived in time (late cells still fill the cache).
    val deadline = System.nanoTime() + alongTimeout.toNanos
    val all = wanted.zip(fetched).flatMap { case (cell, f) =>
      Try(Await.result(f, math.max(0L, deadline - System.nanoTime()).nanos)).getOrElse(cached(cell))
    }
    all.groupBy(_.hex).values.map(_.minBy(_.seenSecondsAgo)).toSeq
  }

  /**
   * The cached cells covering a viewport, as they are: never waits on adsb.lol. Stale or missing cells are queued for a
   * background refresh, so a client that polls again a few seconds later sees the view fill in.
   */
  def cellsIn(south: Double, west: Double, north: Double, east: Double): TrafficCells = {
    val now = Instant.now()
    val wanted = cellsCovering(south, west, north, east)
    val centerLat = (south + north) / 2
    val centerLon = (west + east) / 2
    def fetchedAt(c: Cell): Instant = Option(cells.get(c)).fold(Instant.EPOCH)(_._1)
    // Never-fetched and oldest cells first (so the edges of a wide view fill in), then closest to the centre.
    val stale = wanted
      .filter(c => Duration.between(fetchedAt(c), now).compareTo(ttlFor(wanted.size)) > 0)
      .sortBy(c => (fetchedAt(c), math.abs(c.lat - centerLat) + math.abs(c.lon - centerLon)))
    stale.filter(c => inFlight.add(c)).take(maxFetchesPerRequest).foreach { cell =>
      Future {
        try
          Try(LiveTrafficClient.aircraftAround(cell.lat, cell.lon, cellRadiusNm)).fold(
            e => log.warn("adsb.lol fetch failed", safe("cell", cell.toString), unsafe("error", e.getMessage)),
            { case (_, aircraft) => cells.put(cell, (Instant.now(), aircraft)): Unit }
          )
        finally inFlight.remove(cell): Unit
      }
    }
    val cached = wanted.flatMap(c => Option(cells.get(c)).map { case (at, aircraft) => CellSnapshot(c, at, aircraft) })
    TrafficCells(cached, complete = cached.size == wanted.size)
  }
}

object LiveTrafficService {
  final case class Cell(lat: Double, lon: Double)

  private val cellDeg = 5.0
  val cellRadiusNm = 250 // adsb.lol's maximum
  val maxFetchesPerRequest = 20
  private val cachedMaxAge = Duration.ofSeconds(120)
  private val alongStepKm = 400.0
  private val alongMaxAge = Duration.ofSeconds(180)
  private val alongTimeout = Duration.ofSeconds(15)
  // A whole-country view is more cells than the API budget refreshes quickly, but planes there are a few pixels wide.
  private def ttlFor(cellsInView: Int): Duration =
    if (cellsInView > 24) Duration.ofSeconds(180) else Duration.ofSeconds(60)

  private[live] def extrapolate(a: LiveAircraft, ageSeconds: Double): LiveAircraft =
    (a.groundSpeedKts, a.trackDeg) match {
      case (Some(gs), Some(track)) if !a.onGround && ageSeconds > 1 && ageSeconds < 300 =>
        val (lat, lon) = RouteAnalyzer.destination(a.lat, a.lon, track, gs * 1.852 * ageSeconds / 3600)
        a.copy(lat = lat, lon = lon)
      case _ => a
    }
  private val maxCells = 120

  /**
   * Lattice cell centres covering a bounding box; longitude spacing widens with latitude to keep cells ~square. A view
   * wider than [[maxCells]] keeps the cells nearest its centre, so a zoomed-out view still shows the middle of the
   * screen rather than only its southern rows.
   */
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
    if (all.size <= maxCells) all
    else {
      val (centerLat, centerLon) = ((south + north) / 2, (west + east) / 2)
      all.sortBy(c => math.hypot(c.lat - centerLat, (c.lon - centerLon) * math.cos(math.toRadians(c.lat)))).take(
        maxCells
      )
    }
  }
}
