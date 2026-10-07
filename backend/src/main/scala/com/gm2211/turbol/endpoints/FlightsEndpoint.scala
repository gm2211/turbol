/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{FlightRoute, LiveTrafficClient, LiveTrafficService, Place}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder

import java.time.Instant
import scala.util.Try

final case class FlightLookup(query: String, callsign: Option[String], route: Option[FlightRoute], live: Option[AircraftView])

final case class AnalyzeRequest(
  origin: Place,
  destination: Place,
  departure: Option[Instant] = None,
  cruiseAltitudeFt: Option[Int] = None,
  aircraftClass: Option[AircraftClass] = None
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
        analysis <- IO.blocking(
          RouteAnalyzer.analyzeFlight(
            store,
            request.origin.lat,
            request.origin.lon,
            request.destination.lat,
            request.destination.lon,
            request.departure.getOrElse(Instant.now()),
            request.cruiseAltitudeFt,
            request.aircraftClass.getOrElse(AircraftClass.Medium)
          )
        )
        resp <- Ok(analysis.toJson.deepDropNullValues)
      } yield resp
  }

  /** First candidate callsign that has a known route or is airborne right now. */
  private def lookup(query: String): FlightLookup = {
    val now = Instant.now()
    val found = LiveTrafficClient.candidateCallsigns(query).iterator.map { callsign =>
      val live = Try(LiveTrafficClient.byCallsign(callsign)).getOrElse(Seq.empty).filterNot(_.onGround).headOption
      (callsign, traffic.route(callsign), live)
    }.find { case (_, route, live) => route.isDefined || live.isDefined }
    found match {
      case Some((callsign, route, live)) =>
        FlightLookup(query, Some(callsign), route, live.map(LiveEndpoint.view(store, _, now)))
      case None => FlightLookup(query, None, None, None)
    }
  }
}
