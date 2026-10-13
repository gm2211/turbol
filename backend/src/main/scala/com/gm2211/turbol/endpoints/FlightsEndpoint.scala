/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.live.{
  DatedFlight,
  FlightRoute,
  LiveAircraft,
  LiveTrafficClient,
  LiveTrafficService,
  Place,
  RouteSchedule,
  RouteScheduleService,
  ScheduledFlight,
  Stop,
  Timetable
}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import com.gm2211.turbol.services.AirportsService
import io.circe.{Encoder, Json}
import io.circe.generic.auto.*
import io.circe.generic.semiauto.deriveEncoder
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder

import java.time.format.DateTimeFormatter
import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.google.common.cache.{Cache, CacheBuilder}
import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Await, ExecutionContext, Future}
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
  flown: Option[FlownRef] = None,
  /** The path asked for ("filed", "average", "flown", "greatCircle"); the best available one when absent or unavailable. */
  path: Option[String] = None,
  /** The flight's IATA number ("DL286"), to find its last track when `flown` doesn't name one. */
  flightNumber: Option[String] = None,
  /** The chosen date (the origin's local day), for the route's timetable. */
  day: Option[LocalDate] = None
)

/** Names a flown leg: the airframe and the time it took off. */
final case class FlownRef(hex: String, takeoff: Instant)

/** Flights from origin to destination taking off between `from` and `until` (the person's local day). */
final case class ScheduleRequest(
  origin: Place,
  destination: Place,
  from: Instant,
  until: Instant,
  /** The chosen date, as the origin's local calendar day. */
  day: Option[LocalDate] = None
)

final case class RouteSearchRequest(origin: Place, destination: Place)

/** The analyzed route's points, to sample the turbulence around (see RouteCorridor). */
final case class CorridorRequest(points: List[CorridorPoint])

/** [[Corridor]] for the page: EDR bytes in base64, ~5x smaller than a JSON array of them. */
final case class CorridorResponse(levelsFt: Vector[Int], offsetsKm: Vector[Double], points: Int, edr: String)

/** An aircraft in the air now flying origin -> destination. */
final case class RouteFlight(callsign: String, minutesToGo: Option[Int], aircraft: AircraftView)
final case class RouteSearch(flights: List[RouteFlight], checked: Int)

/** Look up a flight by number or callsign, and forecast the turbulence along a flight gate to gate. */
final class FlightsEndpoint(
  store: TurbulenceStore,
  traffic: LiveTrafficService,
  schedules: RouteScheduleService,
  airports: AirportsService
) extends Endpoint
    with BackendSerialization {
  private object Query extends QueryParamDecoderMatcher[String]("q")
  private object DateParam extends QueryParamDecoderMatcher[String]("date")
  private given Encoder[RouteFlight] = deriveEncoder
  private given Encoder[RouteSearch] = deriveEncoder
  private given Encoder[ScheduledFlight] = deriveEncoder
  private given Encoder[RouteSchedule] = deriveEncoder
  private given Encoder[DatedFlight] = deriveEncoder

  override val basePath: String = "/flights"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "lookup" :? Query(q) =>
      IO.blocking(lookup(q)).flatMap(result => Ok(result.toJson.deepDropNullValues))

    case GET -> Root / "dated" :? Query(q) +& DateParam(date) =>
      IO.blocking(
        Try(LocalDate.parse(date)).toOption.flatMap(d => Timetable.dated(q, d, LocalDate.now(ZoneOffset.UTC)))
      ).flatMap {
        // With both airports resolved, so the page can analyze without two more round trips.
        case Some(flight) =>
          IO.blocking {
            val places = Json.obj(
              "originPlace" -> AirportsEndpoint.place(airports, flight.origin).toJson,
              "destinationPlace" -> AirportsEndpoint.place(airports, flight.destination).toJson
            )
            flight.toJson.deepMerge(places).deepDropNullValues
          }.flatMap(Ok(_))
        case None => NotFound()
      }

    case req @ POST -> Root / "route" =>
      for {
        request <- req.as[RouteSearchRequest]
        result <- IO.blocking(searchRoute(request.origin, request.destination))
        resp <- Ok(result.toJson.deepDropNullValues)
      } yield resp

    case req @ POST -> Root / "scheduled" =>
      for {
        request <- req.as[ScheduleRequest]
        result <-
          IO.blocking(schedules.schedule(request.origin, request.destination, request.from, request.until, request.day))
        resp <- Ok(result.toJson.deepDropNullValues)
      } yield resp

    case req @ POST -> Root / "corridor" =>
      for {
        request <- req.as[CorridorRequest]
        corridor <- IO.fromFuture(IO(corridorFor(request.points.toVector)))
        resp <- Ok(
          CorridorResponse(
            corridor.levelsFt,
            corridor.offsetsKm,
            corridor.edr.length / (corridor.levelsFt.length * corridor.offsetsKm.length),
            java.util.Base64.getEncoder.encodeToString(corridor.edr)
          ).toJson
        )
      } yield resp

    case req @ POST -> Root / "analyze" =>
      for {
        request <- req.as[AnalyzeRequest]
        analysis <- IO.blocking(analyze(request))
        // The page asks for the corridor as soon as the analysis lands: it is already being sampled by then.
        _ <- IO(corridorFor(analysis.points.map(p => CorridorPoint(p.lat, p.lon, p.altitudeFt, p.time)).toVector))
        resp <- Ok(analysis.toJson.deepDropNullValues)
      } yield resp
  }

  /**
   * Corridors by route and forecast data, started when an analysis is answered. Sampling one is among the server's
   * heaviest requests (seconds on a small instance), so it overlaps with the analysis' trip to the page.
   */
  private val corridors: Cache[(Vector[CorridorPoint], SameData), Future[Corridor]] =
    CacheBuilder.newBuilder().maximumSize(20).expireAfterWrite(Duration.ofMinutes(10)).build()
  private val corridorPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(2))

  private def corridorFor(points: Vector[CorridorPoint]): Future[Corridor] = {
    val data = store.current
    val f = corridors.get(
      (points.take(RouteCorridor.maxPoints), SameData(data)),
      () => Future(RouteCorridor.build(store, points))(using corridorPool)
    )
    f.failed.foreach(_ => corridors.invalidate((points.take(RouteCorridor.maxPoints), SameData(data))))(using
      ExecutionContext.parasitic
    )
    f
  }

  /**
   * Analyses by path, time, aircraft and forecast data. While its past tracks load the page asks again every 3 s for
   * the same analysis; sampling the route anew each time competed with the page's other requests for the CPU.
   */
  private val analyses: Cache[AnyRef, RouteAnalysis] =
    CacheBuilder.newBuilder().maximumSize(50).expireAfterWrite(Duration.ofMinutes(2)).build()

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
  private def analyze(request: AnalyzeRequest): RouteAnalysis = {
    val (origin, destination) = (request.origin, request.destination)
    val departure = request.departure.getOrElse(Instant.now())
    val distanceKm = RouteAnalyzer.greatCircleKm(origin.lat, origin.lon, destination.lat, destination.lon)
    // The flight's last track on this route: the leg named by a pick from /flights/scheduled, else looked up by flight
    // number (up to two weeks back). The route's past flights for the average are looked up alongside; both get up to
    // `trackWait` (so the forecast shows at once on the best path found so far) and keep searching in the background,
    // marked pending, for the page to ask again.
    val searchDay = request.day.getOrElse(LocalDate.ofInstant(departure, ZoneOffset.UTC))
    val ownSearch = request.flown match {
      case Some(flown) =>
        request.callsign match {
          case Some(callsign) =>
            Try(schedules.legAsync(callsign, flown.hex, flown.takeoff, origin, destination))
              .getOrElse(Future.successful(None))
          case None => Future.successful(None)
        }
      case None =>
        request.flightNumber.orElse(request.callsign.filter(Timetable.parseFlightNumber(_).isDefined)) match {
          case Some(number) =>
            schedules.lastLegAsync(origin, destination, number, departure, request.callsign.filterNot(_ == number))
          case None => Future.successful(None)
        }
    }
    // The live sighting (adsb.lol) is fetched alongside the track lookups rather than after them.
    val liveF = Future(request.callsign.filter(_.nonEmpty).flatMap(liveAircraft(_, fresh = false)))(using
      scala.concurrent.ExecutionContext.global
    )
    // The searches and the sighting share one wait, so the forecast never waits on them for longer than `answerWait`.
    val deadline = System.nanoTime() + FlightsEndpoint.answerWait.toNanos
    def left = math.max(0L, deadline - System.nanoTime()).nanos
    val (recent, routePending) =
      Try(schedules.routeLegs(origin, destination, searchDay, FlightsEndpoint.trackWait)).getOrElse((Seq.empty, false))
    Try(Await.ready(ownSearch, left))
    val own = ownSearch.value.flatMap(_.toOption).flatten
    val ownPending = !ownSearch.isCompleted
    // adsb.lol often takes seconds: the analysis doesn't wait long for it (the sighting is cached, so the page's next
    // refresh, while the track searches finish, picks up the aircraft's type).
    val live = if (own.isDefined) None else Try(Await.result(liveF, left)).toOption.flatten
    val aircraft = own
      .map(l => AircraftResponse.fromAdsb(None, l.typeCode))
      .orElse(live.map(a => AircraftResponse.fromAdsb(a.category, a.typeCode)))
      .getOrElse(AircraftResponse.assumedFor(distanceKm))

    // The paths, best first. The person can pick any available one; otherwise the best available is followed.
    val day = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneOffset.UTC)
    val mean = RouteScheduleService.meanPath(recent)
    val average = mean.filter(_.spreadKm <= RouteScheduleService.similarKm)
    val km = (d: Double) => f"${math.rint(d / 10) * 10}%,.0f"
    val lookback = s"${schedules.lookbackDays} days"
    val options = Seq(
      PathOption(
        "filed",
        "Filed plan",
        available = false,
        Some("Not public: airlines file them only with air traffic control")
      ),
      average match {
        case Some(a) =>
          PathOption(
            "average",
            s"Average of ${a.legs} past flights",
            available = true,
            Some(s"Their tracks from the last $lookback all stay within ${km(a.spreadKm)} km of it")
          )
        case None =>
          val why = mean match {
            case Some(m) =>
              s"Past flights' tracks are too spread to average (up to ${km(m.spreadKm)} km apart; the limit is ${km(RouteScheduleService.similarKm)} km)"
            case None if routePending => "Still looking up the route's past flights"
            case None => s"Fewer than two of the route's flights tracked in the last $lookback"
          }
          PathOption("average", "Average of past flights", available = false, Some(why), pending = routePending)
      },
      own match {
        case Some(l) => PathOption(
            "flown",
            s"${l.callsign}'s last track",
            available = true,
            Some(s"Flown on ${day.format(l.takeoff)} (UTC)")
          )
        case None =>
          val why =
            if (ownPending) "Still looking for its last flight"
            else if (request.flown.isDefined) "Its recent track couldn't be loaded"
            else if (
              request.flightNumber.isDefined || request.callsign.exists(Timetable.parseFlightNumber(_).isDefined)
            )
              s"Not seen flying this route in the last $lookback"
            else "Pick a flight to follow its last track"
          PathOption("flown", "This flight's last track", available = false, Some(why), pending = ownPending)
      },
      PathOption("greatCircle", "Geodesic", available = true, Some("The shortest path between the airports"))
    )
    val chosen = request.path.flatMap(k => options.find(o => o.kind == k && o.available))
      .getOrElse(options.find(_.available).get)
    val path: Option[(IndexedSeq[PathPoint], PathInfo)] = chosen.kind match {
      case "average" =>
        average.map(a =>
          a.points -> PathInfo(
            "average",
            s"Average of ${a.legs} past flights' tracks (last two days, all within ${km(a.spreadKm)} km of it)"
          )
        )
      case "flown" =>
        own.map(l =>
          RouteScheduleService.pathOf(l) -> PathInfo(
            "flown",
            s"${l.callsign}'s last track, flown ${day.format(l.takeoff)} (UTC), moved to this departure",
            Some(l.takeoff)
          )
        )
      case _ => None
    }
    def analyzeOn(path: Option[(IndexedSeq[PathPoint], PathInfo)]): RouteAnalysis = path match {
      case Some((points, info)) => RouteAnalyzer.analyzePath(store, points, departure, aircraft).copy(path = Some(info))
      case None =>
        RouteAnalyzer
          .analyzeFlight(
            store,
            origin.lat,
            origin.lon,
            destination.lat,
            destination.lon,
            departure,
            request.cruiseAltitudeFt,
            aircraft
          )
          .copy(path = Some(PathInfo("greatCircle", "Geodesic: the shortest path between the airports")))
    }
    val data = store.current
    val analysisKey = (origin, destination, departure, request.cruiseAltitudeFt, path, aircraft, SameData(data))
    val analysis = analyses.get(analysisKey, () => analyzeOn(path)).copy(pathOptions = options)

    val typeCode = own.flatMap(_.typeCode).orElse(live.flatMap(_.typeCode))
    val assumed = {
      val km = f"${math.rint(distanceKm / 100) * 100}%,.0f"
      if (aircraft.aircraftClass == AircraftClass.Heavy)
        s"assumed a widebody, as on most $km km routes, with a typical heavy aircraft's sensitivity to turbulence."
      else s"assumed a narrowbody like an A320, as on most $km km routes."
    }
    val note =
      if (own.isDefined || live.isDefined) None
      else if (request.callsign.exists(_.nonEmpty))
        Some(s"Aircraft type unknown (the flight isn't broadcasting it right now): $assumed")
      else Some(s"No flight picked, so the aircraft type is unknown: $assumed")
    val typeNote = aircraft.typeCode.filter(_ => aircraft.basis == "class").map(t =>
      s"No wing-loading data for $t yet: used a typical ${aircraft.aircraftClass.toString.toLowerCase} aircraft."
    )
    analysis.copy(
      aircraftType = typeCode,
      summary = analysis.summary.copy(notes = analysis.summary.notes ++ note ++ typeNote)
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
  /** How long an analysis waits for track lookups before answering without them (they carry on in the background). */
  val trackWait: java.time.Duration = java.time.Duration.ofMillis(300)

  /**
   * How long an analysis waits in all for track lookups and the flight's live sighting (its aircraft type) before
   * answering without them.
   */
  val answerWait: scala.concurrent.duration.FiniteDuration = 500.millis

  /** Whether a route has `origin` immediately followed by `destination` (one leg of a possibly multi-leg route). */
  def flies(stops: Seq[Stop], origin: Place, destination: Place): Boolean =
    stops.sliding(2).exists {
      case Seq(from, to) => from.is(origin) && to.is(destination)
      case _ => false
    }
}

/** Forecast data compared by identity: it is replaced, never changed, when new data arrives. */
final class SameData(val data: AnyRef) {
  override def equals(other: Any): Boolean = other match {
    case o: SameData => o.data eq data
    case _ => false
  }
  override def hashCode: Int = System.identityHashCode(data)
}
