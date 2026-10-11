/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{FlightRoute, LiveAircraft, LiveTrafficClient, LiveTrafficService, Place}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

final case class FlightLookup(
  query: String,
  callsign: Option[String],
  route: Option[FlightRoute],
  live: Option[AircraftView],
  /** Airborne under this callsign but flying a different leg than `route` (flight numbers are reused per leg). */
  otherLeg: Option[AircraftView] = None
)

final case class AnalyzeRequest(
  origin: Place,
  destination: Place,
  departure: Option[Instant] = None,
  cruiseAltitudeFt: Option[Int] = None,
  callsign: Option[String] = None
)

/** Look up a flight by number or callsign, and forecast the turbulence along a flight gate to gate. */
final class FlightsEndpoint(store: TurbulenceStore, traffic: LiveTrafficService)
    extends Endpoint
    with BackendSerialization {
  private object Query extends QueryParamDecoderMatcher[String]("q")

  override val basePath: String = "/flights"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "lookup" :? Query(q) =>
      IO.blocking(lookup(q)).flatMap(result => Ok(result.toJson.deepDropNullValues))

    case req @ POST -> Root / "analyze" =>
      for {
        request <- req.as[AnalyzeRequest]
        analysis <- IO.blocking(analyze(request))
        resp <- Ok(analysis.toJson.deepDropNullValues)
      } yield resp
  }

  /** Live ADS-B sightings from recent lookups, so analyzing a flight just looked up doesn't hit adsb.lol again. */
  private val sightings = new ConcurrentHashMap[String, (Instant, Option[LiveAircraft])]()
  private val sightingTtl = Duration.ofMinutes(10)

  private def liveAircraft(callsign: String, fresh: Boolean): Option[LiveAircraft] = {
    val key = callsign.toUpperCase
    val now = Instant.now()
    val cached = Option(sightings.get(key)).filter(c => !fresh && c._1.plus(sightingTtl).isAfter(now))
    cached.map(_._2).getOrElse {
      val live = Try(LiveTrafficClient.byCallsign(key)).getOrElse(Seq.empty).filterNot(_.onGround).headOption
      if (sightings.size > 1000) sightings.clear()
      sightings.put(key, (now, live))
      live
    }
  }

  /** The aircraft size comes from the flight's ADS-B type, not the user; Medium when the type isn't known. */
  private def analyze(request: AnalyzeRequest): RouteAnalysis = {
    val live = request.callsign.filter(_.nonEmpty).flatMap(liveAircraft(_, fresh = false))
    val aircraftClass = live.fold(AircraftClass.Medium)(a => AircraftClass.fromAdsb(a.category, a.typeCode))
    val analysis = RouteAnalyzer.analyzeFlight(
      store,
      request.origin.lat,
      request.origin.lon,
      request.destination.lat,
      request.destination.lon,
      request.departure.getOrElse(Instant.now()),
      request.cruiseAltitudeFt,
      aircraftClass
    )
    val note =
      if (live.isDefined) None
      else if (request.callsign.exists(_.nonEmpty))
        Some("Aircraft type unknown (the flight isn't broadcasting it right now): assumed a medium aircraft.")
      else Some("No flight number, so the aircraft type is unknown: assumed a medium aircraft.")
    analysis.copy(
      aircraftType = live.flatMap(_.typeCode),
      summary = analysis.summary.copy(notes = analysis.summary.notes ++ note)
    )
  }

  /** First candidate callsign that has a known route or is airborne right now. */
  private def lookup(query: String): FlightLookup = {
    val now = Instant.now()
    val found = LiveTrafficClient.candidateCallsigns(query).iterator.map { callsign =>
      (callsign, traffic.route(callsign), liveAircraft(callsign, fresh = true))
    }.find { case (_, route, live) => route.isDefined || live.isDefined }
    found match {
      case Some((callsign, route, live)) =>
        val (onRoute, elsewhere) = live.partition(a => route.forall(_.isFlownBy(a)))
        val view = (a: LiveAircraft) => LiveEndpoint.view(store, a, now)
        FlightLookup(query, Some(callsign), route, onRoute.map(view), elsewhere.map(view))
      case None => FlightLookup(query, None, None, None)
    }
  }
}
