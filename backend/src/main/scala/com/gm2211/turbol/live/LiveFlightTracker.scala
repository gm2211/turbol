/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.turbol.turbulence.*

import com.google.common.cache.{Cache, CacheBuilder}

import java.time.{Duration, Instant}
import java.util.concurrent.{ConcurrentHashMap, Executors}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.*
import scala.util.Try

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
  ahead: Option[RouteAnalysis],
  /** How strongly this aircraft feels turbulence: its type's wing loading, or its size class. */
  aircraftResponse: AircraftResponse = AircraftResponse.medium
)

/** Builds [[LiveFlightUpdate]]s: current position from adsb.lol, turbulence now and along the rest of the flight. */
final class LiveFlightTracker(store: TurbulenceStore, traffic: LiveTrafficService) {
  import LiveFlightTracker.*

  // adsb.lol's last report per airframe, from fetches that outlived the update that started them.
  private val reports: Cache[String, (Instant, LiveAircraft)] =
    CacheBuilder.newBuilder().maximumSize(5000).expireAfterWrite(Duration.ofMinutes(5)).build()
  private val fetching = new ConcurrentHashMap[String, Future[Option[LiveAircraft]]]()
  private val fetchPool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))

  /** adsb.lol's report for `hex`, one fetch at a time per airframe; a report that comes in late is kept for later. */
  private def fetch(hex: String): Future[Option[LiveAircraft]] =
    fetching.computeIfAbsent(
      hex,
      _ => {
        val f = Future {
          val a = Try(LiveTrafficClient.byHex(hex)).toOption.flatten
          a.foreach(r => reports.put(hex, (Instant.now(), r)))
          a
        }(using fetchPool)
        f.onComplete(_ => fetching.remove(hex))(using ExecutionContext.parasitic)
        f
      }
    )

  /** The latest of adsb.lol's last report and the map's cached traffic, dead-reckoned to now, if seen in 2 min. */
  private def recent(hex: String, now: Instant): Option[LiveAircraft] = {
    val reported = Option(reports.getIfPresent(hex)).map { case (at, a) =>
      val age = Duration.between(at, now).toMillis / 1000.0 + a.seenSecondsAgo
      LiveTrafficService.extrapolate(a, age).copy(seenSecondsAgo = age)
    }
    (reported.toSeq ++ traffic.cached(hex, maxAge = deadReckonFor, now = now))
      .filter(_.seenSecondsAgo <= deadReckonFor.toSeconds)
      .minByOption(_.seenSecondsAgo)
  }

  /**
   * `fresh = false` takes the aircraft from the map's cached traffic when it's there (its first update then doesn't
   * wait on adsb.lol); otherwise, or when adsb.lol fails, adsb.lol's latest report. `fresh = true` asks adsb.lol but
   * waits at most [[adsbWait]] ([[briefWait]] with a recent report in hand; it often takes seconds, which made updates arrive 10-15 s late), else goes on from the
   * last report it has.
   */
  def update(hex: String, now: Instant = Instant.now(), fresh: Boolean = true): LiveFlightUpdate = {
    val key = hex.toLowerCase
    val latest =
      if (fresh) {
        // With a report from the last update or two in hand, adsb.lol only gets a moment; the fetch carries on and
        // its answer serves the next update.
        val fallback = recent(key, now)
        val wait = if (fallback.exists(_.seenSecondsAgo <= 25)) briefWait else adsbWait
        Try(Await.result(fetch(key), wait)).toOption.flatten.orElse(fallback)
      } else traffic.cached(hex, maxAge = deadReckonFor, now = now).orElse(LiveTrafficClient.byHex(hex))
    latest match {
      case None =>
        LiveFlightUpdate(now, "lost", None, AircraftClass.Medium, None, None, usingRoute = false, None, None)
      case Some(a) =>
        val aircraft = AircraftResponse.fromAdsb(a.category, a.typeCode)
        val route = a.callsign.flatMap(traffic.route)
        val alt = a.altitudeFt.filterNot(_ => a.onGround)
        // Only trust the scheduled route if the aircraft is actually flying it (not another leg of the same number).
        val destination = route
          .filter(_.isFlownBy(a))
          .map(r => (r.destination.lat, r.destination.lon))
          .filter { case (lat, lon) => RouteAnalyzer.greatCircleKm(a.lat, a.lon, lat, lon) > 5 }
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
            aircraft
          )
        }
        val edrNow = alt.map(altFt => store.sample(a.lat, a.lon, altFt.toDouble, now).edr).filterNot(_.isNaN)
        LiveFlightUpdate(
          now,
          if (a.onGround) "on-ground" else "tracking",
          Some(a),
          aircraft.aircraftClass,
          edrNow,
          route,
          destination.isDefined,
          ahead.map(upcoming(_, edrNow, aircraft)),
          ahead,
          aircraft
        )
    }
  }
}

object LiveFlightTracker {
  private val horizonMinutes = 60.0
  private val adsbWait = 2.seconds
  private val briefWait = 300.millis

  /**
   * How old a sighting the tracker dead-reckons from rather than wait on adsb.lol: a whole-country map refreshes its
   * cells every 3 minutes, and adsb.lol, rate-limited, made a followed flight's first update wait 12 s for a fresh one.
   */
  private val deadReckonFor = Duration.ofSeconds(290) // LiveTrafficService.extrapolate stops at 300 s

  /** The headline for the next hour: what it's like now, and when the next bumps start and how long they last. */
  def upcoming(ahead: RouteAnalysis, edrNow: Option[Double], aircraft: AircraftResponse): Upcoming = {
    val nowCategory = Thresholds.classify(edrNow.getOrElse(Double.NaN), aircraft)
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
        "No turbulence data here right now."
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
}
