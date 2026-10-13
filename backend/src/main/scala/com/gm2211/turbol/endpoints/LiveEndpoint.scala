/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{
  CellSnapshot,
  LiveAircraft,
  LiveFlightTracker,
  LiveFlightUpdate,
  LiveTrafficClient,
  LiveTrafficService
}
import com.google.common.cache.{Cache, CacheBuilder}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import fs2.Stream
import org.http4s.circe.*
import org.http4s.headers.{`Cache-Control`, `Content-Type`}
import org.http4s.server.middleware.GZip
import org.http4s.{CacheDirective, HttpRoutes, MediaType, ServerSentEvent}

import java.time.Instant
import scala.concurrent.duration.*

/** A live aircraft with the turbulence it is flying through right now (GTGN nowcast at its position and altitude). */
final case class AircraftView(
  hex: String,
  callsign: Option[String],
  registration: Option[String],
  typeCode: Option[String],
  lat: Double,
  lon: Double,
  altitudeFt: Option[Int],
  onGround: Boolean,
  groundSpeedKts: Option[Double],
  trackDeg: Option[Double],
  verticalRateFpm: Option[Int],
  aircraftClass: AircraftClass,
  edr: Option[Double],
  category: TurbulenceCategory,
  /** Epoch ms of the position report, so clients can dead-reckon it to the present. */
  positionTime: Long
)

object LiveEndpoint {
  /** Rounds to `decimals` places, so a coordinate is ~10 bytes of JSON rather than 18. */
  def round(d: Double, decimals: Int): Double = {
    val m = math.pow(10, decimals)
    math.rint(d * m) / m
  }

  /** One aircraft of a cell, pre-encoded: its position (for cropping), identity (for deduping) and JSON. */
  final case class Entry(hex: String, lat: Double, lon: Double, positionTime: Long, json: String)

  /**
   * The entries inside the viewport, each airframe once: cells overlap, so a plane near an edge turns up in several,
   * and the report with the newest position wins. Order of first appearance is kept.
   */
  def withinBounds(
    cells: Seq[Seq[Entry]],
    south: Double,
    west: Double,
    north: Double,
    east: Double
  ): Seq[Entry] = {
    val best = scala.collection.mutable.LinkedHashMap.empty[String, Entry]
    for {
      cell <- cells
      e <- cell
      if e.lat >= south && e.lat <= north && e.lon >= west && e.lon <= east
    }
      if (best.get(e.hex).forall(_.positionTime < e.positionTime)) best.update(e.hex, e)
    best.values.toSeq
  }

  /** A route point with its numbers rounded to what a map or timeline can show. */
  def rounded(p: RoutePoint): RoutePoint =
    p.copy(
      lat = round(p.lat, 4),
      lon = round(p.lon, 4),
      distanceKm = round(p.distanceKm, 2),
      minutesFromStart = round(p.minutesFromStart, 2),
      edr = p.edr.map(round(_, 3))
    )

  /**
   * Decides, per followed-flight stream, which updates carry `ahead.points` (the bulk of an update, 40-500 points): the
   * first, any whose analysis changed, and one every `refreshEvery` otherwise. Updates without points have an empty
   * list, and the client keeps the last it was sent. Not thread-safe: one per stream, used from one fiber at a time.
   */
  final class AheadPointsGate(refreshEvery: Int = 12) {
    private var last: Option[(Int, Seq[TurbulenceCategory], Option[(Double, Double)])] = None
    private var sinceSent = 0

    def apply(u: LiveFlightUpdate): LiveFlightUpdate = u.ahead match {
      case None =>
        last = None
        u
      case Some(a) =>
        val signature = (
          a.points.size,
          a.points.map(_.category),
          a.points.lastOption.map(p => (round(p.lat, 1), round(p.lon, 1)))
        )
        val send = !last.contains(signature) || sinceSent >= refreshEvery
        last = Some(signature)
        sinceSent = if (send) 0 else sinceSent + 1
        u.copy(ahead = Some(a.copy(points = if (send) a.points.map(rounded) else Seq.empty)))
    }
  }

  /** `now` is when `a` was fetched: its position is `a.seenSecondsAgo` older than that. */
  def view(store: TurbulenceStore, a: LiveAircraft, now: Instant): AircraftView = {
    val aircraft = AircraftResponse.fromAdsb(a.category, a.typeCode)
    val edr = a.altitudeFt.filterNot(_ => a.onGround) match {
      case Some(alt) => store.sample(a.lat, a.lon, alt.toDouble, now).edr
      case None => Double.NaN
    }
    AircraftView(
      a.hex,
      a.callsign,
      a.registration,
      a.typeCode,
      round(a.lat, 4),
      round(a.lon, 4),
      a.altitudeFt,
      a.onGround,
      a.groundSpeedKts,
      a.trackDeg,
      a.verticalRateFpm,
      aircraft.aircraftClass,
      Option(edr).filterNot(_.isNaN).map(round(_, 2)),
      Thresholds.classify(edr, aircraft),
      now.toEpochMilli - (a.seenSecondsAgo * 1000).toLong
    )
  }
}

final class LiveEndpoint(store: TurbulenceStore, traffic: LiveTrafficService) extends Endpoint
    with BackendSerialization {
  import LiveEndpoint.*

  // Split derivation: fully automatic derivation of LiveFlightUpdate exceeds the inliner's budget.
  private given Encoder[RouteAnalysis] = deriveEncoder
  private given Encoder[LiveFlightUpdate] = deriveEncoder

  private val tracker = LiveFlightTracker(store, traffic)

  // Keyed by cell, fetch time and nowcast: a refetch or a new nowcast (new turbulence per aircraft) re-encodes.
  private val cellJsonCache: Cache[(LiveTrafficService.Cell, Instant, String), Vector[Entry]] =
    CacheBuilder.newBuilder().maximumSize(500).build()

  private def cellEntries(c: CellSnapshot, nowcastId: String): Vector[Entry] =
    cellJsonCache.get(
      (c.cell, c.fetchedAt, nowcastId),
      () =>
        c.aircraft.filterNot(_.onGround).map { a =>
          val v = view(store, a, c.fetchedAt)
          Entry(v.hex, v.lat, v.lon, v.positionTime, v.toJson.deepDropNullValues.noSpaces)
        }.toVector
    )
  private val updateEvery = 10.seconds
  private val flushAfter = 300.millis
  private val flush = ServerSentEvent(comment = Some("flush"))

  private object South extends QueryParamDecoderMatcher[Double]("south")
  private object West extends QueryParamDecoderMatcher[Double]("west")
  private object North extends QueryParamDecoderMatcher[Double]("north")
  private object East extends QueryParamDecoderMatcher[Double]("east")

  override val basePath: String = "/live"
  // Gzip JSON only: the flight stream is server-sent events, which must not be buffered.
  override val routes: HttpRoutes[IO] = GZip(
    HttpRoutes.of[IO] {
      // Built from each cell's pre-encoded JSON: the instance has a tenth of a CPU, and encoding a whole-country view
      // (~5,000 aircraft, ~900 KB) per request took 20+ s there. Cells overlap and spill past the viewport, so only
      // the aircraft inside it are sent, each once. Clients still dead-reckon and crop, for planes at the edge.
      case GET -> Root / "aircraft" :? South(south) +& West(west) +& North(north) +& East(east) =>
        IO.blocking {
          val traffic0 = traffic.cellsIn(south, west, north, east)
          val data = store.current
          val nowcast = data.nowcast.fold("")(_.id) + data.globalIssued.fold("")(t => s"_g${t.getEpochSecond}")
          val inView = withinBounds(traffic0.cells.map(c => cellEntries(c, nowcast)), south, west, north, east)
          val aircraft = inView.map(_.json).mkString(",")
          s"""{"aircraft":[$aircraft],"complete":${traffic0.complete},"serverTime":${Instant.now().toEpochMilli}}"""
        }.flatMap(json => Ok(json).map(_.withContentType(`Content-Type`(MediaType.application.json))))

      // Server-sent events: one update about the followed flight every 10 s, until the client disconnects. The first
      // starts from the map's cached traffic, so following a plane picked on the map shows its strip at once.
      // A proxy in front of the app held each event until the next write, so every update arrived 10-20 s late:
      // no-transform asks proxies not to compress (and so buffer) it, and a comment just after each event pushes the
      // event out if one still does.
      case GET -> Root / "flights" / hex / "stream" =>
        // The path ahead is most of an update's bytes and rarely changes: see AheadPointsGate.
        val updates = Stream
          .eval(IO(new AheadPointsGate))
          .flatMap { gate =>
            Stream
              .fixedRateStartImmediately[IO](updateEvery)
              .zipWithIndex
              .evalMap { case (_, i) => IO.blocking(gate(tracker.update(hex, fresh = i > 0))).attempt }
              .map {
                case Right(update) => ServerSentEvent(data = Some(update.toJson.deepDropNullValues.noSpaces))
                case Left(e) =>
                  ServerSentEvent(data = Some(s"""{"status":"error","message":"${e.getClass.getSimpleName}"}"""))
              }
          }
          .flatMap(event => Stream.emit(event) ++ Stream.sleep_[IO](flushAfter) ++ Stream.emit(flush))
        Ok(updates).map(
          _.putHeaders(
            `Cache-Control`(CacheDirective.`no-cache`(), CacheDirective.`no-transform`),
            "X-Accel-Buffering" -> "no"
          )
        )

      case GET -> Root / "flights" / hex =>
        IO.blocking(tracker.update(hex)).flatMap(update => Ok(update.toJson.deepDropNullValues))

      case GET -> Root / "aircraft" / hex =>
        IO.blocking(LiveTrafficClient.byHex(hex)).flatMap {
          case Some(a) => Ok(view(store, a, Instant.now()).toJson.deepDropNullValues)
          case None => NotFound(s"$hex is not being tracked right now")
        }
    },
    isZippable = _.contentType.exists(_.mediaType == MediaType.application.json)
  )
}
