/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.time.Instant
import java.time.temporal.ChronoUnit

class RouteAnalyzerTest extends BaseTest {
  import TurbulenceTestData.*

  test("route analysis classifies a uniformly moderate sky and reports the profile") {
    val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
    val store = new TurbulenceStore
    store.setForecast(
      Range.inclusive(0, 10)
        .map(h =>
          uniformFrame(FrameKind.Forecast, now.plus(h.toLong, ChronoUnit.HOURS), Map(3000 -> 0.05, 35000 -> 0.2))
        )
        .toVector
    )
    val analysis =
      RouteAnalyzer.analyzeFlight(store, 40.6413, -73.7781, 33.9416, -118.4085, now, None, AircraftClass.Medium)
    analysis.totalDistanceKm shouldBe 3983.0 +- 10
    analysis.cruiseAltitudeFt shouldBe 35000
    analysis.durationMinutes shouldBe 308.0 +- 5 // 3983 km at 830 km/h + 20 min climb/descent penalty
    analysis.summary.worstCategory shouldBe TurbulenceCategory.Moderate
    analysis.points.head.altitudeFt shouldBe 0
    analysis.points.map(_.altitudeFt).max shouldBe 35000
    analysis.summary.coveragePercent shouldBe 100.0
  }

  test("remaining-route analysis starts at the aircraft's altitude") {
    val store = new TurbulenceStore
    val a = RouteAnalyzer.analyzeRemaining(
      store,
      39.0,
      -98.0,
      37000,
      Some(450),
      Some(270),
      None,
      Instant.now(),
      AircraftClass.Heavy
    )
    a.points.head.altitudeFt shouldBe 37000
    a.points.last.altitudeFt shouldBe 37000
    a.durationMinutes shouldBe 90.0 +- 1
    a.summary.worstCategory shouldBe TurbulenceCategory.NoData
  }
}
