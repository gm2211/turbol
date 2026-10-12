/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{FlightRoute, LiveAircraft, LiveTrafficClient, LiveTrafficService, Place, Stop}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
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

final case class RouteSearchRequest(origin: Place, destination: Place)

/** An aircraft in the air now flying origin -> destination. */
final case class RouteFlight(callsign: String, minutesToGo: Option[Int], aircraft: AircraftView)
final case class RouteSearch(flights: List[RouteFlight], checked: Int)

/** Look up a flight by number or callsign, and forecast the turbulence along a flight gate to gate. */
final class FlightsEndpoint(store: TurbulenceStore, traffic: LiveTrafficService)
    extends Endpoint
    with BackendSerialization {
  private object Query extends QueryParamDecoderMatcher[String]("q")
  private given Encoder[RouteFlight] = deriveEncoder
  private given Encoder[RouteSearch] = deriveEncoder

  override val basePath: String = "/flights"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "lookup" :? Query(q) =>
      IO.blocking(lookup(q)).flatMap(result => Ok(result.toJson.deepDropNullValues))

    case req @ POST -> Root / "route" =>
      for {
        request <- req.as[RouteSearchRequest]
        result <- IO.blocking(searchRoute(request.origin, request.destination))
        resp <- Ok(result.toJson.deepDropNullValues)
      } yield resp

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
        val flying = (a: LiveAircraft) => route.forall(_.isFlownBy(a))
        val view = (a: LiveAircraft) => LiveEndpoint.view(store, a, now)
        FlightLookup(query, Some(callsign), route, live.filter(flying).map(view), live.filterNot(flying).map(view))
      case None => FlightLookup(query, None, None, None)
    }
  }

  /**
   * Flights in the air now from origin to destination: aircraft near the great circle and heading for the
   * destination, kept when their callsign's route has origin then destination as consecutive stops. Routes come in one
   * batch from routeset; if it is down, from adsbdb one callsign at a time for the closest few.
   */
  private def searchRoute(origin: Place, destination: Place): RouteSearch = {
    val now = Instant.now()
    val geometry = FlightRoute("", None, None, origin, destination)
    val candidates = traffic
      .aircraftAlong(origin.lat, origin.lon, destination.lat, destination.lon)
      .filter(a => !a.onGround && a.callsign.isDefined && geometry.isFlownBy(a))
    val routes: Map[String, Seq[Stop]] =
      Try(candidates.grouped(200).flatMap(LiveTrafficClient.routeSet).toMap).toOption.filter(_.nonEmpty).getOrElse {
        candidates.take(25).flatMap { a =>
          val cs = a.callsign.get
          traffic.route(cs).map(r =>
            cs -> Seq(r.origin, r.destination).map(p => Stop(Some(p.code), None, p.lat, p.lon))
          )
        }.toMap
      }
    val flights = candidates.flatMap { a =>
      val cs = a.callsign.get
      routes.get(cs.toUpperCase).filter(FlightsEndpoint.flies(_, origin, destination)).map { _ =>
        val toGoKm = RouteAnalyzer.greatCircleKm(a.lat, a.lon, destination.lat, destination.lon)
        val minutes = a.groundSpeedKts.filter(_ > 50).map(gs => (toGoKm / (gs * 1.852) * 60).round.toInt)
        RouteFlight(cs, minutes, LiveEndpoint.view(store, a, now))
      }
    }
    RouteSearch(flights.sortBy(_.minutesToGo.getOrElse(Int.MaxValue)).toList, candidates.size)
  }
}

object FlightsEndpoint {
  /** Whether a route has `origin` immediately followed by `destination` (one leg of a possibly multi-leg route). */
  def flies(stops: Seq[Stop], origin: Place, destination: Place): Boolean =
    stops.sliding(2).exists {
      case Seq(from, to) => from.is(origin) && to.is(destination)
      case _ => false
    }
}
