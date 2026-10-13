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
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.GZip
import org.http4s.{HttpRoutes, MediaType, ServerSentEvent}

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
      a.lat,
      a.lon,
      a.altitudeFt,
      a.onGround,
      a.groundSpeedKts,
      a.trackDeg,
      a.verticalRateFpm,
      aircraft.aircraftClass,
      Option(edr).filterNot(_.isNaN),
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
  private val cellJsonCache: Cache[(LiveTrafficService.Cell, Instant, String), String] =
    CacheBuilder.newBuilder().maximumSize(500).build()

  private def cellJson(c: CellSnapshot, nowcastId: String): String =
    cellJsonCache.get(
      (c.cell, c.fetchedAt, nowcastId),
      () =>
        c.aircraft.filterNot(_.onGround).map(view(
          store,
          _,
          c.fetchedAt
        ).toJson.deepDropNullValues.noSpaces).mkString(",")
    )
  private val updateEvery = 10.seconds

  private object South extends QueryParamDecoderMatcher[Double]("south")
  private object West extends QueryParamDecoderMatcher[Double]("west")
  private object North extends QueryParamDecoderMatcher[Double]("north")
  private object East extends QueryParamDecoderMatcher[Double]("east")

  override val basePath: String = "/live"
  // Gzip JSON only: the flight stream is server-sent events, which must not be buffered.
  override val routes: HttpRoutes[IO] = GZip(
    HttpRoutes.of[IO] {
      // Built from each cell's pre-encoded JSON: the instance has a tenth of a CPU, and encoding a whole-country view
      // (~5,000 aircraft, ~900 KB) per request took 20+ s there. Clients dead-reckon, dedupe and crop to their viewport.
      case GET -> Root / "aircraft" :? South(south) +& West(west) +& North(north) +& East(east) =>
        IO.blocking {
          val traffic0 = traffic.cellsIn(south, west, north, east)
          val data = store.current
          val nowcast = data.nowcast.fold("")(_.id) + data.globalIssued.fold("")(t => s"_g${t.getEpochSecond}")
          val aircraft = traffic0.cells.map(c => cellJson(c, nowcast)).filter(_.nonEmpty).mkString(",")
          s"""{"aircraft":[$aircraft],"complete":${traffic0.complete},"serverTime":${Instant.now().toEpochMilli}}"""
        }.flatMap(json => Ok(json).map(_.withContentType(`Content-Type`(MediaType.application.json))))

      // Server-sent events: one update about the followed flight every 10 s, until the client disconnects. The first
      // starts from the map's cached traffic, so following a plane picked on the map shows its strip at once.
      case GET -> Root / "flights" / hex / "stream" =>
        val updates = Stream
          .fixedRateStartImmediately[IO](updateEvery)
          .zipWithIndex
          .evalMap { case (_, i) => IO.blocking(tracker.update(hex, fresh = i > 0)).attempt }
          .map {
            case Right(update) => ServerSentEvent(data = Some(update.toJson.deepDropNullValues.noSpaces))
            case Left(e) =>
              ServerSentEvent(data = Some(s"""{"status":"error","message":"${e.getClass.getSimpleName}"}"""))
          }
        Ok(updates)

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
