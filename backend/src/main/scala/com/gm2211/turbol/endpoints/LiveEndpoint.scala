/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{LiveAircraft, LiveFlightTracker, LiveFlightUpdate, LiveTrafficClient, LiveTrafficService}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import fs2.Stream
import org.http4s.circe.*
import org.http4s.{HttpRoutes, ServerSentEvent}

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
  category: TurbulenceCategory
)

final case class TrafficResponse(aircraft: Seq[AircraftView], updated: Instant, complete: Boolean)

object LiveEndpoint {
  def view(store: TurbulenceStore, a: LiveAircraft, now: Instant): AircraftView = {
    val aircraftClass = AircraftClass.fromAdsb(a.category, a.typeCode)
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
      aircraftClass,
      Option(edr).filterNot(_.isNaN),
      Thresholds.classify(edr, aircraftClass)
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
  private val updateEvery = 10.seconds

  private object South extends QueryParamDecoderMatcher[Double]("south")
  private object West extends QueryParamDecoderMatcher[Double]("west")
  private object North extends QueryParamDecoderMatcher[Double]("north")
  private object East extends QueryParamDecoderMatcher[Double]("east")

  override val basePath: String = "/live"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "aircraft" :? South(south) +& West(west) +& North(north) +& East(east) =>
      IO.blocking(traffic.aircraftIn(south, west, north, east)).flatMap { snapshot =>
        val now = Instant.now()
        val aircraft = snapshot.aircraft.filterNot(_.onGround).map(view(store, _, now))
        Ok(TrafficResponse(aircraft, snapshot.updated, snapshot.complete).toJson.deepDropNullValues)
      }

    // Server-sent events: one update about the followed flight every 10 s, until the client disconnects.
    case GET -> Root / "flights" / hex / "stream" =>
      val updates = Stream
        .fixedRateStartImmediately[IO](updateEvery)
        .evalMap(_ => IO.blocking(tracker.update(hex)).attempt)
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
  }
}
