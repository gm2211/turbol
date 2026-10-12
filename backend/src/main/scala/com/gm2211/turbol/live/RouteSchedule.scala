/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.{AircraftClass, PathPoint, RouteAnalyzer}
import com.google.common.cache.{Cache, CacheBuilder}

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * One flight on a route on the chosen day: what the Analyze page lists for a route and date.
 *
 * @param departure        scheduled gate departure (from the timetable), or the observed takeoff moved to the day
 * @param departureLocal   "16:40" at the origin, as the airline publishes it; absent when only ADS-B saw the flight
 * @param arrivalLocal     "06:45" at the destination, `arrivalDayOffset` days later
 * @param takeoff          when the analysis starts: about 20 minutes after the gate time, or the observed takeoff
 * @param hex              the airframe of the flight's most recent leg on this route, whose track the path can follow
 * @param flownTakeoff     that leg's takeoff; with `hex` it names the leg for the analysis
 * @param source           "timetable" (published schedule, with ADS-B for the track) or "adsb" (seen flying only)
 */
final case class ScheduledFlight(
  callsign: String,
  flightNumber: Option[String],
  airline: Option[String],
  departure: Instant,
  arrival: Instant,
  departureLocal: Option[String],
  arrivalLocal: Option[String],
  arrivalDayOffset: Int,
  codeshares: List[String],
  takeoff: Instant,
  landing: Instant,
  typeCode: Option[String],
  aircraftClass: AircraftClass,
  registration: Option[String],
  hex: Option[String],
  flownTakeoff: Option[Instant],
  source: String
)

/**
 * @param timetableDay the day whose timetable was read, when it isn't the chosen day (beyond the pages' reach, the same
 *                     weekday a week earlier)
 */
final case class RouteSchedule(
  flights: List[ScheduledFlight],
  candidates: Int,
  slotsScanned: Int,
  note: Option[String],
  source: String,
  timetableDay: Option[LocalDate] = None
)

/**
 * Flights on a route for a given day. The list and its times come from the published timetable (Timetable); the path
 * each one can follow comes from what flew in the last two days of adsb.lol history: callsigns the route directory
 * lists for the airport pair, found in the hourly heatmap slots (callsign -> airframe), then their flown leg in the
 * airframe's trace. When the timetable can't be read, the ADS-B legs alone make the list, at their observed takeoff
 * times moved to the chosen day.
 */
final class RouteScheduleService(traffic: LiveTrafficService, directory: RouteDirectory = RouteDirectory())
    extends BackendLogging {
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))
  private val flown: Cache[String, Seq[FlownLeg]] =
    CacheBuilder.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(30)).build()

  /** Legs flown from origin to destination in the last two days, oldest first (a callsign can have several). */
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
          4.minutes
        )
        // Every sighting, newest first: the newest can be an onward leg under the same callsign, or a flight that
        // hasn't landed yet, so older ones are tried until the legs on this route are found.
        val sightings = candidates.map { cs =>
          cs -> slots.collect { case (t, Some(index)) if index.hex(cs).isDefined => (index.hex(cs).get, t) }
        }
        val found = Await.result(
          Future.traverse(sightings) { case (cs, seen) =>
            Future(seen.foldLeft(Vector.empty[FlownLeg]) { case (legs, (hex, t)) =>
              val covered = legs.exists(l => !t.isBefore(l.takeoff.minusSeconds(3600)) && !t.isAfter(l.landing))
              if (covered) legs
              else
                Try(FlightHistory.findLeg(cs, hex, t.plusSeconds(900), origin, destination)).toOption.flatten
                  .filterNot(l => legs.exists(o => o.hex == l.hex && o.takeoff == l.takeoff))
                  .fold(legs)(legs :+ _)
            })
          },
          3.minutes
        )
        found.flatten.sortBy(_.takeoff)
      }
    )
    (legs, candidates.size, slotTimes.size)
  }

  /**
   * Flights departing on `day` (the origin's local date) for the timetable, or taking off between `from` and `until`
   * (the person's local day) for the ADS-B fallback.
   */
  def schedule(
    origin: Place,
    destination: Place,
    from: Instant,
    until: Instant,
    day: Option[LocalDate] = None
  ): RouteSchedule = {
    val (legs, candidates, slotsScanned) = flownLegs(origin, destination)
    val wanted = day.getOrElse(LocalDate.ofInstant(from.plusSeconds(12 * 3600), ZoneOffset.UTC))
    val timetable =
      Try(Timetable.route(origin.code, destination.code, wanted, LocalDate.now(ZoneOffset.UTC))).toOption.flatten
    timetable.filter(_._1.nonEmpty) match {
      case Some((flights, readDay)) =>
        val entries = (for {
          f <- icao(origin)
          t <- icao(destination)
        } yield Try(directory.entries(f, t)).getOrElse(Seq.empty)).getOrElse(Seq.empty)
        val matched = RouteScheduleService.matchLegs(flights, legs, entries)
        val shift = Duration.ofDays(ChronoUnit.DAYS.between(readDay, wanted))
        val list = flights.map { f =>
          val leg = matched.get(f.flightNumber)
          val departure = f.departure.plus(shift)
          val takeoff = departure.plus(RouteScheduleService.taxiOut)
          val airborne = leg.map(l => Duration.between(l.takeoff, l.landing))
            .getOrElse(Duration.between(f.departure, f.arrival).minus(RouteScheduleService.taxiOut).minusMinutes(10))
          ScheduledFlight(
            callsign = leg.map(_.callsign).getOrElse(f.flightNumber),
            flightNumber = Some(f.flightNumber),
            airline = Some(f.airline),
            departure = departure,
            arrival = f.arrival.plus(shift),
            departureLocal = Some(f.departureLocal),
            arrivalLocal = Some(f.arrivalLocal),
            arrivalDayOffset = f.arrivalDayOffset,
            codeshares = f.codeshares.toList,
            takeoff = takeoff,
            landing = takeoff.plus(airborne),
            typeCode = leg.flatMap(_.typeCode),
            aircraftClass = AircraftClass.fromAdsb(None, leg.flatMap(_.typeCode)),
            registration = leg.flatMap(_.registration),
            hex = leg.map(_.hex),
            flownTakeoff = leg.map(_.takeoff),
            source = "timetable"
          )
        }.sortBy(_.departure).toList
        RouteSchedule(
          list,
          candidates,
          slotsScanned,
          None,
          "timetable",
          Option.when(readDay != wanted)(readDay)
        )
      case None => fromAdsb(origin, destination, from, until, legs, candidates, slotsScanned, timetable.isDefined)
    }
  }

  private def fromAdsb(
    origin: Place,
    destination: Place,
    from: Instant,
    until: Instant,
    legs: Seq[FlownLeg],
    candidates: Int,
    slotsScanned: Int,
    timetableEmpty: Boolean
  ): RouteSchedule = {
    // The latest leg per callsign: one entry per flight.
    val latest = legs.groupBy(_.callsign).values.map(_.maxBy(_.takeoff)).toSeq
    val flights = latest.flatMap { leg =>
      RouteScheduleService.onDay(leg.takeoff, from, until).map { takeoff =>
        val route = traffic.route(leg.callsign)
        val landing = takeoff.plus(Duration.between(leg.takeoff, leg.landing))
        ScheduledFlight(
          callsign = leg.callsign,
          flightNumber = route.flatMap(_.iataCallsign),
          airline = route.flatMap(_.airline),
          departure = takeoff,
          arrival = landing,
          departureLocal = None,
          arrivalLocal = None,
          arrivalDayOffset = 0,
          codeshares = Nil,
          takeoff = takeoff,
          landing = landing,
          typeCode = leg.typeCode,
          aircraftClass = AircraftClass.fromAdsb(None, leg.typeCode),
          registration = leg.registration,
          hex = Some(leg.hex),
          flownTakeoff = Some(leg.takeoff),
          source = "adsb"
        )
      }
    }.sortBy(_.takeoff).toList
    val note =
      if (origin.icao.isEmpty && origin.code.length != 4 || destination.icao.isEmpty && destination.code.length != 4)
        Some("Couldn't tell the airports' ICAO codes, so no flights could be looked up.")
      else if (candidates == 0) Some("No airline flight numbers are listed for this route.")
      else if (flights.isEmpty) Some("None of this route's flight numbers flew in the last two days.")
      else if (timetableEmpty)
        Some("No published timetable for this route: flights seen flying it in the last two days.")
      else Some(
        "The timetable couldn't be read: flights seen flying this route in the last two days, at their takeoff times."
      )
    RouteSchedule(flights, candidates, slotsScanned, note, "adsb")
  }

  /** The leg flown on airframe `hex` that took off at `flownTakeoff`, for the analysis. */
  def leg(callsign: String, hex: String, flownTakeoff: Instant, origin: Place, destination: Place): Option[FlownLeg] =
    flownLegs(origin, destination)._1
      .find(l => l.hex.equalsIgnoreCase(hex) && l.takeoff == flownTakeoff)
      .orElse(FlightHistory.findLeg(callsign, hex, flownTakeoff.plusSeconds(600), origin, destination))

  /** The route's legs from the last two days if a schedule search already loaded them (never scans history itself). */
  def cachedLegs(origin: Place, destination: Place): Option[Seq[FlownLeg]] =
    for {
      f <- icao(origin)
      t <- icao(destination)
      legs <- Option(flown.getIfPresent(s"$f-$t"))
    } yield legs

  private def icao(place: Place): Option[String] =
    place.icao.filter(_.length == 4).orElse(Some(place.code.toUpperCase).filter(_.length == 4))
}

/** The mean of several flown tracks; `spreadKm` is how far the furthest track strays from it. */
final case class AveragePath(points: Vector[PathPoint], legs: Int, spreadKm: Double)

object RouteScheduleService {
  /** Gate to wheels-up, roughly: the analysis of a timetabled flight starts this long after its departure time. */
  val taxiOut: Duration = Duration.ofMinutes(20)

  /** Signed minutes from `a`'s time of day (UTC) to `b`'s, in (-720, 720]. */
  private def timeOfDayMinutes(a: Instant, b: Instant): Long = {
    val m = Math.floorMod(b.getEpochSecond - a.getEpochSecond, 86400L) / 60
    if (m > 720) m - 1440 else m
  }

  /**
   * The newest flown leg for each timetabled flight. A leg belongs to a flight when its callsign's directory number
   * is the flight's number and it took off between 30 minutes before and three hours after the gate time; or, for
   * callsigns that aren't the flight number (Emirates flies EK206 as UAE5RQ), when the same airline's leg took off
   * within 15 minutes before to three hours after it. The airline's ICAO code is learned from its numbered callsigns.
   */
  def matchLegs(flights: Seq[TimetableFlight], legs: Seq[FlownLeg], entries: Seq[RouteEntry]): Map[String, FlownLeg] = {
    val entryOf = entries.map(e => e.callsign -> e).toMap
    val airlines: Map[String, Set[String]] = flights.flatMap { f =>
      entries.filter(_.number == f.number).map(e => f.carrierIata -> e.airline)
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    val numbers = flights.map(_.number).toSet
    flights.flatMap { f =>
      val own =
        legs.filter(l => entryOf.get(l.callsign).exists(e => airlines.getOrElse(f.carrierIata, Set.empty)(e.airline)))
      val gap = (l: FlownLeg) => timeOfDayMinutes(f.departure, l.takeoff)
      val numbered = own.filter(l => entryOf(l.callsign).number == f.number && gap(l) >= -30 && gap(l) <= 180)
      val timed = own.filter(l => !numbers(entryOf(l.callsign).number) && gap(l) >= -15 && gap(l) <= 180)
      numbered.maxByOption(_.takeoff).orElse(timed.maxByOption(_.takeoff)).map(f.flightNumber -> _)
    }.toMap
  }

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
    meanPath(legs, n).filter(_.spreadKm <= similarKm)

  /** The point-by-point mean of two or more tracks, however far apart they are. */
  def meanPath(legs: Seq[FlownLeg], n: Int = 150): Option[AveragePath] =
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
      Some(AveragePath(mean, legs.size, spread))
    }

  /** The same time of day as `observed`, on the day [from, until), if it falls inside it. */
  def onDay(observed: Instant, from: Instant, until: Instant): Option[Instant] = {
    val day = 86400L
    val offset = Math.floorMod(observed.getEpochSecond - from.getEpochSecond, day)
    Some(from.plusSeconds(offset)).filter(_.isBefore(until))
  }
}
