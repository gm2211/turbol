/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{
  FlightRoute,
  LiveAircraft,
  LiveTrafficClient,
  LiveTrafficService,
  Place,
  RouteSchedule,
  RouteScheduleService,
  ScheduledFlight,
  Stop
}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Encoder
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder

import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, ZoneOffset}
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

/**
 * @param flown a leg this flight number flew recently (from /flights/scheduled): the analysis follows its track,
 *              altitudes and timing, and uses its aircraft type, instead of a great circle
 */
final case class AnalyzeRequest(
  origin: Place,
  destination: Place,
  departure: Option[Instant] = None,
  cruiseAltitudeFt: Option[Int] = None,
  callsign: Option[String] = None,
  flown: Option[FlownRef] = None
)

/** Names a flown leg: the airframe and the time it took off. */
final case class FlownRef(hex: String, takeoff: Instant)

/** Flights from origin to destination taking off between `from` and `until` (the person's local day). */
final case class ScheduleRequest(origin: Place, destination: Place, from: Instant, until: Instant)

final case class RouteSearchRequest(origin: Place, destination: Place)

/** An aircraft in the air now flying origin -> destination. */
final case class RouteFlight(callsign: String, minutesToGo: Option[Int], aircraft: AircraftView)
final case class RouteSearch(flights: List[RouteFlight], checked: Int)

/** Look up a flight by number or callsign, and forecast the turbulence along a flight gate to gate. */
final class FlightsEndpoint(
  store: TurbulenceStore,
  traffic: LiveTrafficService,
  schedules: RouteScheduleService
) extends Endpoint
    with BackendSerialization {
  private object Query extends QueryParamDecoderMatcher[String]("q")
  private given Encoder[RouteFlight] = deriveEncoder
  private given Encoder[RouteSearch] = deriveEncoder
  private given Encoder[ScheduledFlight] = deriveEncoder
  private given Encoder[RouteSchedule] = deriveEncoder

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

    case req @ POST -> Root / "scheduled" =>
      for {
        request <- req.as[ScheduleRequest]
        result <- IO.blocking(schedules.schedule(request.origin, request.destination, request.from, request.until))
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

  /**
   * How strongly the aircraft feels turbulence comes from the flight's ADS-B type, not the user: its wing loading when
   * the type is in AircraftResponse's table, else its size class. When the type isn't known, the class typical for the
   * route's length (a widebody for long haul), marked as assumed.
   */
  private def analyze(request: AnalyzeRequest): RouteAnalysis =
    (for {
      flown <- request.flown
      callsign <- request.callsign
      leg <-
        Try(schedules.leg(callsign, flown.hex, flown.takeoff, request.origin, request.destination)).toOption.flatten
    } yield {
      val start = leg.takeoff
      val path = leg.points.map(p =>
        PathPoint(Duration.between(start, p.time).toMillis / 60000.0, p.lat, p.lon, p.altitudeFt)
      )
      val aircraft = AircraftResponse.fromAdsb(None, leg.typeCode)
      val analysis = RouteAnalyzer.analyzePath(store, path, request.departure.getOrElse(Instant.now()), aircraft)
      val day = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneOffset.UTC).format(start)
      val typeNote = leg.typeCode.filter(_ => aircraft.basis == "class").map(t =>
        s"No wing-loading data for $t yet: used a typical ${aircraft.aircraftClass.toString.toLowerCase} aircraft."
      )
      analysis.copy(
        aircraftType = leg.typeCode,
        summary = analysis.summary.copy(notes = analysis.summary.notes ++ typeNote),
        path =
          Some(PathInfo("flown", s"${leg.callsign}'s flown track on $day (UTC), moved to this departure", Some(start)))
      )
    }).getOrElse(analyzeGreatCircle(request))

  private def analyzeGreatCircle(request: AnalyzeRequest): RouteAnalysis = {
    val live = request.callsign.filter(_.nonEmpty).flatMap(liveAircraft(_, fresh = false))
    val distanceKm = RouteAnalyzer.greatCircleKm(
      request.origin.lat,
      request.origin.lon,
      request.destination.lat,
      request.destination.lon
    )
    val aircraft =
      live.fold(AircraftResponse.assumedFor(distanceKm))(a => AircraftResponse.fromAdsb(a.category, a.typeCode))
    val analysis = RouteAnalyzer.analyzeFlight(
      store,
      request.origin.lat,
      request.origin.lon,
      request.destination.lat,
      request.destination.lon,
      request.departure.getOrElse(Instant.now()),
      request.cruiseAltitudeFt,
      aircraft
    )
    val assumed = {
      val km = f"${math.rint(distanceKm / 100) * 100}%,.0f"
      if (aircraft.aircraftClass == AircraftClass.Heavy)
        s"assumed a widebody, as on most $km km routes, with a typical heavy aircraft's sensitivity to turbulence."
      else s"assumed a narrowbody like an A320, as on most $km km routes."
    }
    val note =
      if (live.isDefined) None
      else if (request.callsign.exists(_.nonEmpty))
        Some(s"Aircraft type unknown (the flight isn't broadcasting it right now): $assumed")
      else Some(s"No flight picked, so the aircraft type is unknown: $assumed")
    val typeNote = aircraft.typeCode.filter(_ => aircraft.basis == "class").map(t =>
      s"No wing-loading data for $t yet: used a typical ${aircraft.aircraftClass.toString.toLowerCase} aircraft."
    )
    val pathNote =
      request.flown.map(_ => "This flight's earlier track couldn't be loaded, so the route is a great circle.")
    analysis.copy(
      aircraftType = live.flatMap(_.typeCode),
      summary = analysis.summary.copy(notes = analysis.summary.notes ++ pathNote ++ note ++ typeNote),
      path = Some(PathInfo("greatCircle", "Great-circle route: filed routes aren't public"))
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
