/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.time.{Duration, Instant, ZoneOffset}
import scala.annotation.unused
import scala.math.*

/** One sampled point along a flight. */
final case class RoutePoint(
  lat: Double,
  lon: Double,
  distanceKm: Double,
  altitudeFt: Int,
  minutesFromStart: Double,
  time: Instant,
  edr: Option[Double],
  category: TurbulenceCategory,
  source: EdrSource,
  nearGround: Boolean
)

/** A run of consecutive points in the same category, for the timeline. */
final case class Segment(category: TurbulenceCategory, startMinute: Double, endMinute: Double)

final case class RouteSummary(
  verdict: String,
  worstCategory: TurbulenceCategory,
  maxEdr: Option[Double],
  worstMinute: Option[Double],
  worstLat: Option[Double],
  worstLon: Option[Double],
  minutesByCategory: Map[String, Double],
  percentByCategory: Map[String, Double],
  coveragePercent: Double,
  notes: Seq[String]
)

final case class RouteAnalysis(
  totalDistanceKm: Double,
  durationMinutes: Double,
  start: Instant,
  cruiseAltitudeFt: Int,
  aircraftClass: AircraftClass,
  points: Seq[RoutePoint],
  segments: Seq[Segment],
  summary: RouteSummary,
  dataSources: DataSources,
  aircraftType: Option[String] = None,
  /** How strongly this aircraft feels turbulence (its wing loading, or its size class when the type is unknown). */
  aircraft: AircraftResponse = AircraftResponse.medium,
  path: Option[PathInfo] = None,
  /** The paths the analysis could follow, best first, and whether each is available; `path` is the one followed. */
  pathOptions: Seq[PathOption] = Seq.empty
)

/**
 * Where the route's line came from: "filed" (the filed flight plan), "average" (the mean of the route's recent tracks),
 * "flown" (the track this flight flew recently, `flownOn`, shifted to the new departure) or "greatCircle".
 */
final case class PathInfo(kind: String, description: String, flownOn: Option[Instant] = None)

/**
 * A path the person can pick: `label` names it; `note` says why it isn't available, or what it is when it is;
 * `pending` while the tracks it needs are still being looked up (asking again shortly may find it).
 */
final case class PathOption(
  kind: String,
  label: String,
  available: Boolean,
  note: Option[String] = None,
  pending: Boolean = false
)

/** One point of a path to analyse: minutes after the start, position and altitude. */
final case class PathPoint(minutes: Double, lat: Double, lon: Double, altitudeFt: Double)

/** @param globalIssued the GFS cycle behind the coarse global layer used outside GTG, when loaded */
final case class DataSources(
  nowcastValid: Option[Instant],
  forecastIssued: Option[Instant],
  forecastUntil: Option[Instant],
  globalIssued: Option[Instant] = None,
  /** Sources still filling in after a server restart ("forecast", "global"): missing data may be on its way. */
  loading: Seq[String] = Seq.empty
)

/**
 * "How bumpy will this flight be": a great-circle route with a climb / cruise / descent profile, sampled in space,
 * altitude and time against the GTGN nowcast and GTG forecast (a port of data-pipeline/route_forecast.py).
 */
object RouteAnalyzer {

  private val utcFormat = java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm 'UTC'").withZone(ZoneOffset.UTC)
  private[turbulence] def utc(t: Instant): String = utcFormat.format(t)

  /**
   * When some of `times` (en-route points without data) are past the global layer's reach: how far it reaches, and
   * roughly when a GFS cycle reaching the last of them will be served (cycles are 6-hourly, complete about 5 hours after
   * their init, and take about an hour to fetch). None while the global layer is still loading or empty.
   */
  private[turbulence] def beyondGlobal(times: Seq[Instant], data: TurbulenceData): Option[(Instant, Instant)] =
    if (data.loading("global")) None
    else
      data.global.lastOption.map(_.validTime.plusSeconds((TurbulenceStore.globalSlackHours * 3600).toLong)).flatMap {
        until =>
          times.filter(_.isAfter(until)).maxOption.map { last =>
            val lead = TurbulenceDataUpdater.globalForecastHours.last + TurbulenceStore.globalSlackHours.toInt
            val needed = last.minusSeconds(lead * 3600L).getEpochSecond
            val init = Math.floorDiv(needed + 6 * 3600 - 1, 6 * 3600L) * 6 * 3600
            until -> Instant.ofEpochSecond(init + 6 * 3600)
          }
      }
  private val earthRadiusKm = 6371.0
  val cruiseSpeedKmh = 830.0
  private val climbRateFtPerKm = 35000.0 / 150.0 // 150 km to reach FL350, as in the prototype
  private val climbDescentPenaltyH = 20.0 / 60.0 // climbing and descending are slower than cruise
  val nearGroundFt = 2000.0

  def greatCircleKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val (p1, p2) = (toRadians(lat1), toRadians(lat2))
    val dLat = p2 - p1
    val dLon = toRadians(lon2 - lon1)
    val a = pow(sin(dLat / 2), 2) + cos(p1) * cos(p2) * pow(sin(dLon / 2), 2)
    2 * earthRadiusKm * asin(min(1.0, sqrt(a)))
  }

  /** Initial great-circle bearing from point 1 to point 2, in degrees clockwise from north. */
  def bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val (p1, p2, dl) = (toRadians(lat1), toRadians(lat2), toRadians(lon2 - lon1))
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    (toDegrees(atan2(y, x)) + 360) % 360
  }

  /** Smallest angle between two headings, 0..180. */
  def angleDiff(a: Double, b: Double): Double = {
    val d = abs(a - b) % 360
    if (d > 180) 360 - d else d
  }

  /** Point at fraction f along the great circle (spherical interpolation). */
  def intermediate(lat1: Double, lon1: Double, lat2: Double, lon2: Double, f: Double): (Double, Double) = {
    val (p1, l1, p2, l2) = (toRadians(lat1), toRadians(lon1), toRadians(lat2), toRadians(lon2))
    val d = greatCircleKm(lat1, lon1, lat2, lon2) / earthRadiusKm
    if (d < 1e-9) return (lat1, lon1)
    val a = sin((1 - f) * d) / sin(d)
    val b = sin(f * d) / sin(d)
    val x = a * cos(p1) * cos(l1) + b * cos(p2) * cos(l2)
    val y = a * cos(p1) * sin(l1) + b * cos(p2) * sin(l2)
    val z = a * sin(p1) + b * sin(p2)
    (toDegrees(atan2(z, sqrt(x * x + y * y))), toDegrees(atan2(y, x)))
  }

  /** Destination point given start, bearing (deg) and distance. */
  def destination(lat: Double, lon: Double, bearingDeg: Double, distanceKm: Double): (Double, Double) = {
    val d = distanceKm / earthRadiusKm
    val (p1, l1, b) = (toRadians(lat), toRadians(lon), toRadians(bearingDeg))
    val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
    val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
    (toDegrees(p2), ((toDegrees(l2) + 540) % 360) - 180)
  }

  /** Typical cruise altitude for a stage length when the caller doesn't give one. */
  def defaultCruiseFt(distanceKm: Double): Int =
    if (distanceKm < 400) 24000 else if (distanceKm < 900) 31000 else 35000

  /** Minutes after takeoff at each distance along a gate-to-gate flight of `totalKm` (the profile analyzeFlight uses). */
  def plannedMinutesAt(totalKm: Double, cruiseFt: Option[Int] = None): Double => Double =
    Profile(totalKm, 0, cruiseFt.getOrElse(defaultCruiseFt(totalKm)), cruiseSpeedKmh, includeClimb = true).minutesAt

  /**
   * Analyse a whole flight, gate to gate.
   */
  def analyzeFlight(
    store: TurbulenceStore,
    fromLat: Double,
    fromLon: Double,
    toLat: Double,
    toLon: Double,
    departure: Instant,
    cruiseFt: Option[Int],
    aircraft: AircraftResponse
  ): RouteAnalysis = {
    val total = greatCircleKm(fromLat, fromLon, toLat, toLon)
    val cruise = cruiseFt.getOrElse(defaultCruiseFt(total))
    val profile = Profile(total, startAltitudeFt = 0, cruise, groundSpeedKmh = cruiseSpeedKmh, includeClimb = true)
    val at = alongProfile(f => intermediate(fromLat, fromLon, toLat, toLon, f), profile)
    analyze(store, total, at, profile.cruiseFt, departure, aircraft)
  }

  /**
   * Analyse a flight along a given path (a track flown on an earlier day), keeping its altitudes and timing and
   * starting at `departure`. Gaps in the path (no ADS-B coverage over oceans) are crossed on the great circle.
   */
  def analyzePath(
    store: TurbulenceStore,
    path: IndexedSeq[PathPoint],
    departure: Instant,
    aircraft: AircraftResponse
  ): RouteAnalysis = {
    require(path.size >= 2, "a path needs at least two points")
    val cumulative = path.indices.scanLeft(0.0) { (km, k) =>
      if (k == 0) km else km + greatCircleKm(path(k - 1).lat, path(k - 1).lon, path(k).lat, path(k).lon)
    }.tail
    val total = cumulative.last
    val cruise = (rint(path.map(_.altitudeFt).max / 1000) * 1000).toInt
    def at(@unused f: Double, km: Double): (Double, Double, Double, Double) = {
      val idx = cumulative.indexWhere(_ >= km)
      val hi = if (idx < 0) path.size - 1 else math.max(1, idx)
      val (a, b) = (path(hi - 1), path(hi))
      val span = cumulative(hi) - cumulative(hi - 1)
      val g = if (span <= 0) 0.0 else math.min(1.0, math.max(0.0, (km - cumulative(hi - 1)) / span))
      val (lat, lon) = intermediate(a.lat, a.lon, b.lat, b.lon, g)
      (lat, lon, a.altitudeFt + g * (b.altitudeFt - a.altitudeFt), a.minutes + g * (b.minutes - a.minutes))
    }
    analyze(store, total, at, cruise, departure, aircraft)
  }

  /**
   * Analyse the rest of a flight that is already airborne: from its current position, altitude and ground speed
   * to the destination (descending at the end), or straight ahead along its track when the destination is unknown.
   */
  def analyzeRemaining(
    store: TurbulenceStore,
    lat: Double,
    lon: Double,
    altitudeFt: Int,
    groundSpeedKts: Option[Double],
    trackDeg: Option[Double],
    destination: Option[(Double, Double)],
    now: Instant,
    aircraft: AircraftResponse,
    lookaheadMinutes: Double = 90
  ): RouteAnalysis = {
    val speedKmh = groundSpeedKts.filter(_ > 80).map(_ * 1.852).getOrElse(cruiseSpeedKmh)
    destination match {
      case Some((toLat, toLon)) =>
        val total = greatCircleKm(lat, lon, toLat, toLon)
        val profile = Profile(total, altitudeFt, altitudeFt, speedKmh, includeClimb = false)
        analyze(
          store,
          total,
          alongProfile(f => intermediate(lat, lon, toLat, toLon, f), profile),
          altitudeFt,
          now,
          aircraft
        )
      case None =>
        val total = speedKmh * lookaheadMinutes / 60
        val bearing = trackDeg.getOrElse(0.0)
        val profile = Profile(total, altitudeFt, altitudeFt, speedKmh, includeClimb = false, descend = false)
        val at = alongProfile(f => this.destination(lat, lon, bearing, total * f), profile)
        analyze(store, total, at, altitudeFt, now, aircraft)
    }
  }

  /** Vertical and speed profile along the route. */
  final private case class Profile(
    totalKm: Double,
    startAltitudeFt: Int,
    cruiseFt: Int,
    groundSpeedKmh: Double,
    includeClimb: Boolean,
    descend: Boolean = true
  ) {
    private val climbKm =
      if (includeClimb) min(totalKm / 2, max(0, cruiseFt - startAltitudeFt) / climbRateFtPerKm) else 0
    private val descentKm = if (descend) min(totalKm - climbKm, cruiseFt / climbRateFtPerKm) else 0
    // A short hop never reaches the nominal cruise altitude.
    private val peakFt: Double =
      if (includeClimb) min(cruiseFt.toDouble, climbKm * climbRateFtPerKm + startAltitudeFt) else cruiseFt.toDouble
    private val descentStartKm = totalKm - descentKm

    def altitudeAt(km: Double): Double =
      if (km < climbKm) startAltitudeFt + (peakFt - startAltitudeFt) * km / climbKm
      else if (descend && km > descentStartKm && descentKm > 0) peakFt * (totalKm - km) / descentKm
      else peakFt

    /** Minutes from start: cruise speed plus a time penalty that accrues across the climb and descent ramps. */
    def minutesAt(km: Double): Double = {
      val base = km / groundSpeedKmh * 60
      val half = climbDescentPenaltyH * 60 / 2
      val climbPart = if (climbKm > 0) half * min(1.0, km / climbKm) else 0.0
      val descentPart = if (descentKm > 0 && km > descentStartKm) half * (km - descentStartKm) / descentKm else 0.0
      base + climbPart + descentPart
    }
  }

  /** Position, altitude and minutes from start at each distance along a route with a climb / cruise / descent profile. */
  private def alongProfile(
    pointAt: Double => (Double, Double),
    profile: Profile
  ): (Double, Double) => (Double, Double, Double, Double) =
    (f, km) => {
      val (lat, lon) = pointAt(f)
      (lat, lon, profile.altitudeAt(km), profile.minutesAt(km))
    }

  /** `at(f, km)` gives latitude, longitude, altitude (ft) and minutes from the start at fraction f / km along the route. */
  private def analyze(
    store: TurbulenceStore,
    totalKm: Double,
    at: (Double, Double) => (Double, Double, Double, Double),
    cruiseFt: Int,
    start: Instant,
    aircraft: AircraftResponse
  ): RouteAnalysis = {
    val data = store.current
    val n = max(40, min(500, (totalKm / 15).toInt + 2))
    val points = (0 until n).map { k =>
      val f = if (n == 1) 0.0 else k.toDouble / (n - 1)
      val km = totalKm * f
      val (lat, lon, alt, minutes) = at(f, km)
      val time = start.plusSeconds((minutes * 60).toLong)
      val sample = store.sampleIn(data, lat, lon, alt, time)
      val edr = Option(sample.edr).filterNot(_.isNaN)
      RoutePoint(
        lat,
        lon,
        km,
        rint(alt).toInt,
        minutes,
        time,
        edr,
        Thresholds.classify(sample.edr, aircraft),
        sample.source,
        alt < nearGroundFt
      )
    }
    val forecastUntil = data.forecast.lastOption.map(_.validTime)
    RouteAnalysis(
      totalDistanceKm = totalKm,
      durationMinutes = points.lastOption.fold(0.0)(_.minutesFromStart),
      start = start,
      cruiseAltitudeFt = cruiseFt,
      aircraftClass = aircraft.aircraftClass,
      points = points,
      segments = segments(points),
      summary = summarize(points, data, start, forecastUntil),
      dataSources = DataSources(
        data.nowcast.map(_.validTime),
        data.forecastIssued,
        forecastUntil,
        data.globalIssued,
        loading = Seq("forecast", "global").filter(data.loading)
      ),
      aircraft = aircraft
    )
  }

  private def segments(points: Seq[RoutePoint]): Seq[Segment] =
    points
      .foldLeft(Vector.empty[Segment]) { (acc, p) =>
        acc.lastOption match {
          case Some(last) if last.category == p.category =>
            acc.updated(acc.size - 1, last.copy(endMinute = p.minutesFromStart))
          case Some(last) => acc.updated(acc.size - 1, last.copy(endMinute = p.minutesFromStart)) :+
              Segment(p.category, p.minutesFromStart, p.minutesFromStart)
          case None => Vector(Segment(p.category, p.minutesFromStart, p.minutesFromStart))
        }
      }

  private def fmtMinutes(m: Double): String = {
    val total = rint(m).toInt
    if (total >= 60) f"${total / 60}h${total % 60}%02dm" else s"${total} min"
  }

  private def summarize(
    points: Seq[RoutePoint],
    data: TurbulenceData,
    start: Instant,
    forecastUntil: Option[Instant]
  ): RouteSummary = {
    val enRoute = points.filterNot(_.nearGround)
    val step = if (points.size > 1) points.last.minutesFromStart / (points.size - 1) else 0.0
    val withData = enRoute.filter(_.edr.isDefined)
    val counts = TurbulenceCategory.ordered.map(c => c -> enRoute.count(_.category == c)).toMap
    val worst = withData.maxByOption(_.edr.getOrElse(0.0))
    val worstCategory = worst.map(_.category).getOrElse(TurbulenceCategory.NoData)
    val coverage = if (enRoute.isEmpty) 0.0 else 100.0 * withData.size / enRoute.size

    val notes = Seq.newBuilder[String]
    val global = enRoute.count(_.source == EdrSource.Global)
    if (global > 0) notes +=
      f"${100.0 * global / enRoute.size}%.0f%% of the route is outside GTG coverage (contiguous US and nearby) and uses " +
        "the coarser global forecast (WAFS and NOAA GFS, about 28 km), which is less precise than GTG's 3 km."
    if (coverage < 99.5) {
      val outside = enRoute.count(p => p.edr.isEmpty && LambertGrid.nearestIndex(p.lat, p.lon) < 0)
      val stillLoading = Seq("forecast", "global").filter(data.loading)
      if (stillLoading.nonEmpty)
        notes +=
          s"The ${stillLoading.map(l => if (l == "global") "global forecast" else "GTG forecast").mkString(" and ")}" +
            " is still loading after a server update; parts of the route without data will fill in within a few minutes."
      else if (outside > 0) notes +=
        f"${100.0 * outside / enRoute.size}%.0f%% of the route is outside GTG coverage (contiguous US and nearby)" +
          " and the global forecast has no data for it yet."
      forecastUntil.foreach { until =>
        if (stillLoading.isEmpty && points.exists(p => p.time.isAfter(until) && p.edr.isEmpty))
          notes +=
            s"The forecast only reaches $until (18 h after the latest GTG cycle); later parts of the flight have no data yet."
      }
      if (data.forecast.isEmpty && data.nowcast.isEmpty) notes += "Turbulence data is still loading."
    }
    if (Duration.between(Instant.now(), start).toHours > 18)
      notes += "This departure is beyond the 18-hour GTG forecast horizon. Check back closer to the day."
    // Past the global layer's last frame nothing has data yet: say how far it reaches and when the flight will be in it.
    val beyond = RouteAnalyzer.beyondGlobal(enRoute.filter(_.edr.isEmpty).map(_.time), data)
    beyond.foreach { case (until, ready) =>
      if (withData.nonEmpty)
        notes +=
          s"The forecast only reaches ${RouteAnalyzer.utc(until)}; the rest of the flight fills in after about " +
            s"${RouteAnalyzer.utc(ready)}."
    }

    val verdict =
      if (withData.isEmpty)
        beyond.fold("No turbulence data for this route yet.") { case (until, ready) =>
          s"No forecast yet: forecasts reach ${RouteAnalyzer.utc(until)}, and this flight is later. Check back after " +
            s"about ${RouteAnalyzer.utc(ready)}."
        }
      else {
        val at = worst.map(p => fmtMinutes(p.minutesFromStart)).getOrElse("")
        worstCategory match {
          case TurbulenceCategory.Extreme =>
            "Expect a rough ride: extreme turbulence is forecast on this route, and airlines would route around it."
          case TurbulenceCategory.Severe =>
            s"Buckle up: severe turbulence is forecast around $at into the flight. Expect a bumpy ride or a reroute."
          case TurbulenceCategory.Moderate =>
            s"Some bumps likely, worst around $at into the flight. Keep your seatbelt fastened."
          case TurbulenceCategory.Light =>
            "Mostly smooth, with occasional light bumps. Nothing to worry about."
          case _ => "Smooth ride: no significant turbulence expected."
        }
      }

    RouteSummary(
      verdict = verdict,
      worstCategory = worstCategory,
      maxEdr = worst.flatMap(_.edr),
      worstMinute = worst.map(_.minutesFromStart),
      worstLat = worst.map(_.lat),
      worstLon = worst.map(_.lon),
      minutesByCategory = counts.map { case (c, k) => c.toString -> rint(k * step * 10) / 10 },
      percentByCategory = counts.map { case (c, k) =>
        c.toString -> (if (enRoute.isEmpty) 0.0 else rint(1000.0 * k / enRoute.size) / 10)
      },
      coveragePercent = rint(coverage * 10) / 10,
      notes = notes.result()
    )
  }
}
