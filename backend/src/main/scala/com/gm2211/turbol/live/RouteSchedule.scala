/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.{AircraftClass, PathPoint, RouteAnalyzer}
import com.google.common.cache.{Cache, CacheBuilder}

import java.time.{Duration, Instant}
import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * One flight on a route, as flown in the last day: what the Analyze page lists for a route and date.
 *
 * @param takeoff     when it is expected to take off on the requested day: the observed takeoff's time of day
 * @param flownTakeoff the observed takeoff the path comes from; with `hex` it names the leg for the analysis
 */
final case class ScheduledFlight(
  callsign: String,
  flightNumber: Option[String],
  airline: Option[String],
  takeoff: Instant,
  landing: Instant,
  typeCode: Option[String],
  aircraftClass: AircraftClass,
  registration: Option[String],
  hex: String,
  flownTakeoff: Instant
)

final case class RouteSchedule(flights: List[ScheduledFlight], candidates: Int, slotsScanned: Int, note: Option[String])

/**
 * Flights on a route for a given day, predicted from the last day of adsb.lol history: callsigns the route directory
 * lists for the airport pair, found in the half-hour heatmap slots (callsign -> airframe), then their flown leg in the
 * airframe's trace. Airline timetables are daily for most routes, so yesterday's takeoff time of day is the forecast
 * for any day; filed flight plans aren't public.
 */
final class RouteScheduleService(traffic: LiveTrafficService, directory: RouteDirectory = RouteDirectory())
    extends BackendLogging {
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))
  private val flown: Cache[String, Seq[FlownLeg]] =
    CacheBuilder.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(30)).build()

  /** Legs flown from origin to destination in the last day, latest per callsign. */
  def flownLegs(origin: Place, destination: Place): (Seq[FlownLeg], Int, Int) = {
    val (from, to) = (icao(origin), icao(destination))
    val candidates = (for {
      f <- from
      t <- to
    } yield directory.callsigns(f, t)).getOrElse(Seq.empty)
    if (candidates.isEmpty) return (Seq.empty, 0, 0)
    val slotTimes = FlightHistory.slotsToScan()
    val legs = flown.get(
      s"${from.get}-${to.get}",
      () => {
        val slots = Await.result(
          Future.traverse(slotTimes)(t => Future(t -> Try(FlightHistory.slot(t)).toOption)),
          3.minutes
        )
        // Newest sighting per callsign: the airframe that flew it most recently.
        val sightings = candidates.flatMap { cs =>
          slots.collectFirst { case (t, Some(index)) if index.hex(cs).isDefined => (cs, index.hex(cs).get, t) }
        }
        val found = Await.result(
          Future.traverse(sightings) { case (cs, hex, seen) =>
            Future(Try(FlightHistory.findLeg(cs, hex, seen.plusSeconds(900), origin, destination)).toOption.flatten)
          },
          2.minutes
        )
        found.flatten.sortBy(_.takeoff)
      }
    )
    (legs, candidates.size, slotTimes.size)
  }

  /** Flights taking off between `from` and `until` (the person's local day), from the legs flown in the last day. */
  def schedule(origin: Place, destination: Place, from: Instant, until: Instant): RouteSchedule = {
    val (legs, candidates, slotsScanned) = flownLegs(origin, destination)
    val flights = legs.flatMap { leg =>
      RouteScheduleService.onDay(leg.takeoff, from, until).map { takeoff =>
        val route = traffic.route(leg.callsign)
        ScheduledFlight(
          callsign = leg.callsign,
          flightNumber = route.flatMap(_.iataCallsign),
          airline = route.flatMap(_.airline),
          takeoff = takeoff,
          landing = takeoff.plus(Duration.between(leg.takeoff, leg.landing)),
          typeCode = leg.typeCode,
          aircraftClass = AircraftClass.fromAdsb(None, leg.typeCode),
          registration = leg.registration,
          hex = leg.hex,
          flownTakeoff = leg.takeoff
        )
      }
    }.sortBy(_.takeoff).toList
    val note =
      if (origin.icao.isEmpty && origin.code.length != 4 || destination.icao.isEmpty && destination.code.length != 4)
        Some("Couldn't tell the airports' ICAO codes, so no flights could be looked up.")
      else if (candidates == 0) Some("No airline flight numbers are listed for this route.")
      else if (flights.isEmpty) Some("None of this route's flight numbers flew in the last day.")
      else None
    RouteSchedule(flights, candidates, slotsScanned, note)
  }

  /** The leg flown on airframe `hex` that took off at `flownTakeoff`, for the analysis. */
  def leg(callsign: String, hex: String, flownTakeoff: Instant, origin: Place, destination: Place): Option[FlownLeg] =
    flownLegs(origin, destination)._1
      .find(l => l.hex.equalsIgnoreCase(hex) && l.takeoff == flownTakeoff)
      .orElse(FlightHistory.findLeg(callsign, hex, flownTakeoff.plusSeconds(600), origin, destination))

  /** The route's legs from the last day if a schedule search already loaded them (never scans history itself). */
  def cachedLegs(origin: Place, destination: Place): Seq[FlownLeg] =
    (for {
      f <- icao(origin)
      t <- icao(destination)
      legs <- Option(flown.getIfPresent(s"$f-$t"))
    } yield legs).getOrElse(Seq.empty)

  private def icao(place: Place): Option[String] =
    place.icao.filter(_.length == 4).orElse(Some(place.code.toUpperCase).filter(_.length == 4))
}

/** The mean of several flown tracks; `spreadKm` is how far the furthest track strays from it. */
final case class AveragePath(points: Vector[PathPoint], legs: Int, spreadKm: Double)

object RouteScheduleService {
  /** Tracks count as one route, worth averaging, when none strays further than this from their mean. */
  val similarKm = 200.0

  /** Minutes after takeoff, position and altitude along a leg. */
  def pathOf(leg: FlownLeg): Vector[PathPoint] =
    leg.points.map(p =>
      PathPoint(Duration.between(leg.takeoff, p.time).toMillis / 60000.0, p.lat, p.lon, p.altitudeFt)
    )

  /** A path resampled at `n` evenly spaced fractions of its length. */
  def resample(path: IndexedSeq[PathPoint], n: Int): Vector[PathPoint] = {
    val cumulative = path.indices.scanLeft(0.0) { (km, k) =>
      if (k == 0) km
      else km + RouteAnalyzer.greatCircleKm(path(k - 1).lat, path(k - 1).lon, path(k).lat, path(k).lon)
    }.tail
    val total = cumulative.last
    (0 until n).toVector.map { i =>
      val km = total * i / (n - 1)
      val idx = cumulative.indexWhere(_ >= km)
      val hi = if (idx < 0) path.size - 1 else math.max(1, idx)
      val (a, b) = (path(hi - 1), path(hi))
      val span = cumulative(hi) - cumulative(hi - 1)
      val g = if (span <= 0) 0.0 else math.min(1.0, math.max(0.0, (km - cumulative(hi - 1)) / span))
      val (lat, lon) = RouteAnalyzer.intermediate(a.lat, a.lon, b.lat, b.lon, g)
      PathPoint(a.minutes + g * (b.minutes - a.minutes), lat, lon, a.altitudeFt + g * (b.altitudeFt - a.altitudeFt))
    }
  }

  /**
   * The average of the route's flown tracks, point by point along their length (positions averaged on the sphere,
   * altitudes and times as numbers), when there are at least two and they agree within [[similarKm]]: on routes
   * that move with the jet stream day to day (the North Atlantic tracks) they often don't, and no average is given.
   */
  def averagePath(legs: Seq[FlownLeg], n: Int = 150): Option[AveragePath] =
    if (legs.size < 2) None
    else {
      val tracks = legs.map(l => resample(pathOf(l), n))
      val mean = (0 until n).toVector.map { i =>
        val ps = tracks.map(_(i))
        val (x, y, zz) = ps.foldLeft((0.0, 0.0, 0.0)) { case ((x, y, zz), p) =>
          val (la, lo) = (math.toRadians(p.lat), math.toRadians(p.lon))
          (x + math.cos(la) * math.cos(lo), y + math.cos(la) * math.sin(lo), zz + math.sin(la))
        }
        PathPoint(
          ps.map(_.minutes).sum / ps.size,
          math.toDegrees(math.atan2(zz, math.sqrt(x * x + y * y))),
          math.toDegrees(math.atan2(y, x)),
          ps.map(_.altitudeFt).sum / ps.size
        )
      }
      val spread = tracks.flatMap(t =>
        t.indices.map(i => RouteAnalyzer.greatCircleKm(t(i).lat, t(i).lon, mean(i).lat, mean(i).lon))
      ).max
      Option.when(spread <= similarKm)(AveragePath(mean, legs.size, spread))
    }

  /** The same time of day as `observed`, on the day [from, until), if it falls inside it. */
  def onDay(observed: Instant, from: Instant, until: Instant): Option[Instant] = {
    val day = 86400L
    val offset = Math.floorMod(observed.getEpochSecond - from.getEpochSecond, day)
    Some(from.plusSeconds(offset)).filter(_.isBefore(until))
  }
}
