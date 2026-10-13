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
   * Flights departing on `day` (the origin's local date) from the timetable, or, when it can't be read, flights taking
   * off between `from` and `until` (the person's local day) from two days of ADS-B. With a timetable this answers at
   * once: each flight's last track is looked up in the background (lastLeg) and shows once found.
   */
  def schedule(
    origin: Place,
    destination: Place,
    from: Instant,
    until: Instant,
    day: Option[LocalDate] = None
  ): RouteSchedule = {
    val wanted = day.getOrElse(LocalDate.ofInstant(from.plusSeconds(12 * 3600), ZoneOffset.UTC))
    val timetable =
      Try(Timetable.route(origin.code, destination.code, wanted, LocalDate.now(ZoneOffset.UTC))).toOption.flatten
    timetable.filter(_._1.nonEmpty) match {
      case Some((flights, readDay)) =>
        val shift = Duration.ofDays(ChronoUnit.DAYS.between(readDay, wanted))
        val list = flights.map { f =>
          val departure = f.departure.plus(shift)
          // Started now so it's ready when the flight is picked; shown here only if already found.
          val search = lastLegAsync(origin, destination, f.flightNumber, departure)
          val leg = search.value.flatMap(_.toOption).flatten
          val takeoff = departure.plus(RouteScheduleService.taxiOut)
          val airborne = leg
            .map(l => Duration.between(l.takeoff, l.landing))
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
        RouteSchedule(list, flights.size, 0, None, "timetable", Option.when(readDay != wanted)(readDay))
      case None =>
        val (legs, candidates, slotsScanned) = flownLegs(origin, destination)
        fromAdsb(origin, destination, from, until, legs, candidates, slotsScanned, timetable.isDefined)
    }
  }

  // Track searches: by flight, remembered for a while whatever they found, so repeated asks don't search again.
  private val searchPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
  // Timetable reads for routeLegs, kept apart from searchPool so a waiting analysis never blocks a search.
  private val lookupPool = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  private val searches: Cache[String, Future[Option[FlownLeg]]] =
    CacheBuilder.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofHours(1)).build()

  /** How far back a flight's last track is looked for: many long-haul flights run a few days a week. */
  val lookbackDays = 14

  /**
   * The most recent leg flight `flightNumber` (IATA, "DL286") flew from origin to destination in the last
   * [[lookbackDays]] days, found in one heatmap slot per day (the half hour after its gate time, when it's taxiing or
   * climbing near the origin, in ADS-B coverage), newest first, three days at a time. Its callsigns come from the route
   * directory: the numbered one (DAL286) and the airline's non-numeric ones on this pair (UAE5RQ), plus `callsign` if
   * known. Started once per flight and shared.
   */
  def lastLegAsync(
    origin: Place,
    destination: Place,
    flightNumber: String,
    departure: Instant,
    callsign: Option[String] = None
  ): Future[Option[FlownLeg]] = {
    val key = s"${icao(origin).getOrElse(origin.code)}-${icao(destination).getOrElse(destination.code)}-$flightNumber"
    searches.get(
      key,
      () =>
        Future {
          val entries = (for {
            f <- icao(origin)
            t <- icao(destination)
          } yield Try(directory.entries(f, t)).getOrElse(Seq.empty)).getOrElse(Seq.empty)
          val callsigns = Timetable
            .parseFlightNumber(flightNumber)
            .map(fn => RouteScheduleService.callsignsFor(entries, fn._2))
            .getOrElse(Seq.empty) ++ callsign.toSeq
          if (callsigns.isEmpty) None
          else searchLastLeg(origin, destination, callsigns.distinct, departure)
        }(using searchPool)
    )
  }

  private def searchLastLeg(
    origin: Place,
    destination: Place,
    callsigns: Seq[String],
    departure: Instant
  ): Option[FlownLeg] = {
    val slots = RouteScheduleService.dailySlots(departure, Instant.now(), lookbackDays)
    slots.grouped(3).iterator.map { batch =>
      val found = Await.result(
        Future.traverse(batch) { t =>
          Future {
            Try(FlightHistory.slot(t)).toOption.flatMap { index =>
              callsigns.iterator.flatMap(cs => index.hex(cs).map(cs -> _)).flatMap { case (cs, hex) =>
                Try(FlightHistory.findLeg(cs, hex, t.plusSeconds(900), origin, destination)).toOption.flatten
              }.nextOption()
            }
          }
        },
        3.minutes
      )
      found.flatten.maxByOption(_.takeoff)
    }.collectFirst { case Some(leg) => leg }
  }

  /**
   * The route's past flights for the average: each timetabled flight's last track (the day's timetable, or the nearest
   * one in reach), plus any legs a two-day scan already found. Waits up to `wait` for searches still running; the
   * Boolean says whether any were still running.
   */
  def routeLegs(origin: Place, destination: Place, day: LocalDate, wait: Duration): (Seq[FlownLeg], Boolean) = {
    val deadline = System.nanoTime() + wait.toNanos
    def left = scala.concurrent.duration.Duration.fromNanos(math.max(0L, deadline - System.nanoTime()))
    // The timetable itself can take seconds on a cold route, so it counts against `wait` too.
    val searchesF = Future {
      val timetable =
        Try(Timetable.route(origin.code, destination.code, day, LocalDate.now(ZoneOffset.UTC))).toOption.flatten
      timetable.toSeq.flatMap(_._1).map(f => lastLegAsync(origin, destination, f.flightNumber, f.departure))
    }(using lookupPool)
    val searches = Try(Await.result(searchesF, left)).toOption
    searches.foreach(s => Try(Await.ready(Future.sequence(s)(using implicitly, lookupPool), left)))
    val found = searches.toSeq.flatten.flatMap(_.value.flatMap(_.toOption).flatten)
    val legs = (found ++ cachedLegs(origin, destination).getOrElse(Seq.empty)).distinctBy(l => (l.hex, l.takeoff))
    (legs, searches.forall(_.exists(!_.isCompleted)))
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

  /** Tracks count as one route, worth averaging, when none strays further than this from their mean. */
  val similarKm = 200.0

  /**
   * The callsigns flight `number` can show up under on a route: the directory's callsign with that number (DAL286),
   * then its airline's callsigns on the pair that aren't plain numbers (Emirates flies EK206 as UAE5RQ).
   */
  def callsignsFor(entries: Seq[RouteEntry], number: String): Seq[String] = {
    val numbered = entries.filter(_.number == number)
    val airlines = numbered.map(_.airline).toSet
    numbered.map(_.callsign) ++
      entries.filter(e => airlines(e.airline) && !e.number.forall(_.isDigit)).map(_.callsign)
  }

  /**
   * One heatmap slot a day for the last `days` days, newest first: the half hour starting 30 minutes after the gate
   * time of day, skipping any not yet complete and uploaded.
   */
  def dailySlots(departure: Instant, now: Instant, days: Int): Seq[Instant] = {
    val secondOfDay = Math.floorMod(departure.getEpochSecond + 1800, 86400L)
    val today = Math.floorDiv(now.getEpochSecond, 86400L) * 86400L
    (0 to days)
      .map(k => Instant.ofEpochSecond((today - k * 86400L + secondOfDay) / 1800 * 1800))
      .filter(_.isBefore(now.minusSeconds(3600)))
      .take(days)
  }

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
