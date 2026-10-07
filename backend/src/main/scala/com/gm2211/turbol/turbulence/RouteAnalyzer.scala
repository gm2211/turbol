/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.time.{Duration, Instant}
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
  dataSources: DataSources
)

final case class DataSources(
  nowcastValid: Option[Instant],
  forecastIssued: Option[Instant],
  forecastUntil: Option[Instant]
)

/**
 * "How bumpy will this flight be": a great-circle route with a climb / cruise / descent profile, sampled in space,
 * altitude and time against the GTGN nowcast and GTG forecast (a port of data-pipeline/route_forecast.py).
 */
object RouteAnalyzer {
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
    aircraftClass: AircraftClass
  ): RouteAnalysis = {
    val total = greatCircleKm(fromLat, fromLon, toLat, toLon)
    val cruise = cruiseFt.getOrElse(defaultCruiseFt(total))
    val profile = Profile(total, startAltitudeFt = 0, cruise, groundSpeedKmh = cruiseSpeedKmh, includeClimb = true)
    analyze(store, total, f => intermediate(fromLat, fromLon, toLat, toLon, f), profile, departure, aircraftClass)
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
    aircraftClass: AircraftClass,
    lookaheadMinutes: Double = 90
  ): RouteAnalysis = {
    val speedKmh = groundSpeedKts.filter(_ > 80).map(_ * 1.852).getOrElse(cruiseSpeedKmh)
    destination match {
      case Some((toLat, toLon)) =>
        val total = greatCircleKm(lat, lon, toLat, toLon)
        val profile = Profile(total, altitudeFt, altitudeFt, speedKmh, includeClimb = false)
        analyze(store, total, f => intermediate(lat, lon, toLat, toLon, f), profile, now, aircraftClass)
      case None =>
        val total = speedKmh * lookaheadMinutes / 60
        val bearing = trackDeg.getOrElse(0.0)
        val profile = Profile(total, altitudeFt, altitudeFt, speedKmh, includeClimb = false, descend = false)
        analyze(store, total, f => this.destination(lat, lon, bearing, total * f), profile, now, aircraftClass)
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

  private def analyze(
    store: TurbulenceStore,
    totalKm: Double,
    pointAt: Double => (Double, Double),
    profile: Profile,
    start: Instant,
    aircraftClass: AircraftClass
  ): RouteAnalysis = {
    val data = store.current
    val n = max(40, min(500, (totalKm / 15).toInt + 2))
    val points = (0 until n).map { k =>
      val f = if (n == 1) 0.0 else k.toDouble / (n - 1)
      val km = totalKm * f
      val (lat, lon) = pointAt(f)
      val alt = profile.altitudeAt(km)
      val minutes = profile.minutesAt(km)
      val time = start.plusSeconds((minutes * 60).toLong)
      val sample = store.sampleAt(data, LambertGrid.nearestIndex(lat, lon), alt, time)
      val edr = Option(sample.edr).filterNot(_.isNaN)
      RoutePoint(
        lat,
        lon,
        km,
        rint(alt).toInt,
        minutes,
        time,
        edr,
        Thresholds.classify(sample.edr, aircraftClass),
        sample.source,
        alt < nearGroundFt
      )
    }
    val forecastUntil = data.forecast.lastOption.map(_.validTime)
    RouteAnalysis(
      totalDistanceKm = totalKm,
      durationMinutes = points.lastOption.fold(0.0)(_.minutesFromStart),
      start = start,
      cruiseAltitudeFt = profile.cruiseFt,
      aircraftClass = aircraftClass,
      points = points,
      segments = segments(points),
      summary = summarize(points, data, start, forecastUntil),
      dataSources = DataSources(data.nowcast.map(_.validTime), data.forecastIssued, forecastUntil)
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
    if (coverage < 99.5) {
      val outside = enRoute.count(p => p.edr.isEmpty && LambertGrid.nearestIndex(p.lat, p.lon) < 0)
      if (outside > 0) notes +=
        f"${100.0 * outside / enRoute.size}%.0f%% of the route is outside GTG coverage (contiguous US and nearby)."
      forecastUntil.foreach { until =>
        if (points.lastOption.exists(_.time.isAfter(until)))
          notes +=
            s"The forecast only reaches $until (18 h after the latest GTG cycle); later parts of the flight have no data yet."
      }
      if (data.forecast.isEmpty && data.nowcast.isEmpty) notes += "Turbulence data is still loading."
    }
    if (Duration.between(Instant.now(), start).toHours > 18)
      notes += "This departure is beyond the 18-hour GTG forecast horizon. Check back closer to the day."

    val verdict =
      if (withData.isEmpty) "No turbulence data for this route yet."
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
