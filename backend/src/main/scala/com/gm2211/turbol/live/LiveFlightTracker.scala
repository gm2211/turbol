/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.turbol.turbulence.*

import java.time.Instant
import scala.math.*

/** What's coming up for a tracked flight in the next hour, in passenger terms. */
final case class Upcoming(
  headline: String,
  nowCategory: TurbulenceCategory,
  nextBumpCategory: Option[TurbulenceCategory],
  nextBumpInMinutes: Option[Double],
  nextBumpForMinutes: Option[Double],
  worstNextHour: TurbulenceCategory
)

/** One update for a flight someone is following. */
final case class LiveFlightUpdate(
  time: Instant,
  status: String,
  aircraft: Option[LiveAircraft],
  aircraftClass: AircraftClass,
  edrNow: Option[Double],
  route: Option[FlightRoute],
  usingRoute: Boolean,
  upcoming: Option[Upcoming],
  ahead: Option[RouteAnalysis]
)

/** Builds [[LiveFlightUpdate]]s: current position from adsb.lol, turbulence now and along the rest of the flight. */
final class LiveFlightTracker(store: TurbulenceStore, traffic: LiveTrafficService) {
  private val horizonMinutes = 60.0

  def update(hex: String, now: Instant = Instant.now()): LiveFlightUpdate =
    LiveTrafficClient.byHex(hex) match {
      case None =>
        LiveFlightUpdate(now, "lost", None, AircraftClass.Medium, None, None, usingRoute = false, None, None)
      case Some(a) =>
        val aircraftClass = AircraftClass.fromAdsb(a.category, a.typeCode)
        val route = a.callsign.flatMap(traffic.route)
        val alt = a.altitudeFt.filterNot(_ => a.onGround)
        // Only trust the scheduled route if the aircraft is actually heading for that destination.
        val destination = route
          .map(r => (r.destination.lat, r.destination.lon))
          .filter { case (lat, lon) =>
            a.trackDeg.forall(track => angleDiff(bearing(a.lat, a.lon, lat, lon), track) < 90) &&
            RouteAnalyzer.greatCircleKm(a.lat, a.lon, lat, lon) > 5
          }
        val ahead = alt.map { altFt =>
          RouteAnalyzer.analyzeRemaining(
            store,
            a.lat,
            a.lon,
            altFt,
            a.groundSpeedKts,
            a.trackDeg,
            destination,
            now,
            aircraftClass
          )
        }
        val edrNow = alt.map(altFt => store.sample(a.lat, a.lon, altFt.toDouble, now).edr).filterNot(_.isNaN)
        LiveFlightUpdate(
          now,
          if (a.onGround) "on-ground" else "tracking",
          Some(a),
          aircraftClass,
          edrNow,
          route,
          destination.isDefined,
          ahead.map(summarize(_, edrNow, aircraftClass)),
          ahead
        )
    }

  private def summarize(ahead: RouteAnalysis, edrNow: Option[Double], aircraftClass: AircraftClass): Upcoming = {
    val nowCategory = Thresholds.classify(edrNow.getOrElse(Double.NaN), aircraftClass)
    val window = ahead.points.filter(p => p.minutesFromStart <= horizonMinutes && !p.nearGround)
    val worst = window.map(_.category).maxByOption(_.rank).getOrElse(TurbulenceCategory.NoData)
    val bumpy = (p: RoutePoint) => p.category.rank >= TurbulenceCategory.Light.rank
    val firstBump = window.find(p => bumpy(p) && p.minutesFromStart > 0.5)
    val bumpEnd = firstBump.flatMap(b => window.find(p => p.minutesFromStart > b.minutesFromStart && !bumpy(p)))
    val lasting = for {
      b <- firstBump
      e <- bumpEnd
    } yield e.minutesFromStart - b.minutesFromStart
    val worstInBump = firstBump.map { b =>
      window
        .filter(p =>
          p.minutesFromStart >= b.minutesFromStart && bumpEnd.forall(p.minutesFromStart < _.minutesFromStart)
        )
        .map(_.category)
        .maxBy(_.rank)
    }
    val label = (c: TurbulenceCategory) => c.toString.toLowerCase
    val headline = (nowCategory, firstBump, worstInBump) match {
      case (TurbulenceCategory.NoData, None, _) if worst == TurbulenceCategory.NoData =>
        "No turbulence data here (GTG covers the contiguous US)."
      case (now, _, _) if now.rank >= TurbulenceCategory.Light.rank =>
        s"${now.toString} turbulence right now."
      case (_, Some(b), Some(c)) =>
        val inMin = max(1, rint(b.minutesFromStart).toInt)
        val forMin = lasting.map(m => s" for about ${max(1, rint(m).toInt)} min").getOrElse("")
        s"Smooth now. ${label(c).capitalize} bumps expected in about $inMin min$forMin."
      case _ => "Smooth now, and smooth for the next hour."
    }
    Upcoming(headline, nowCategory, worstInBump, firstBump.map(_.minutesFromStart), lasting, worst)
  }

  private def bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val (p1, p2, dl) = (toRadians(lat1), toRadians(lat2), toRadians(lon2 - lon1))
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    (toDegrees(atan2(y, x)) + 360) % 360
  }

  private def angleDiff(a: Double, b: Double): Double = {
    val d = abs(a - b) % 360
    if (d > 180) 360 - d else d
  }
}
