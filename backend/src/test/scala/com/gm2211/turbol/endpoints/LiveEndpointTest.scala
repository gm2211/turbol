/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import com.gm2211.turbol.endpoints.LiveEndpoint.{AheadPointsGate, Entry}
import com.gm2211.turbol.live.LiveFlightUpdate
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BaseTest

import java.time.Instant

class LiveEndpointTest extends BaseTest {
  private def entry(hex: String, lat: Double, lon: Double, time: Long = 1000L) =
    Entry(hex, lat, lon, time, s"""{"hex":"$hex"}""")

  test("only aircraft inside the viewport are sent") {
    val cell = Seq(entry("in", 40.0, -100.0), entry("north", 51.0, -100.0), entry("west", 40.0, -121.0))
    val hexes = LiveEndpoint.withinBounds(Seq(cell), 30, -120, 50, -80).map(_.hex)
    hexes shouldBe Seq("in")
  }

  test("an aircraft in overlapping cells is sent once, with its newest position") {
    val a = Seq(entry("dup", 40.0, -100.0, time = 1000L), entry("a-only", 41.0, -101.0))
    val b = Seq(entry("dup", 40.1, -100.1, time = 5000L), entry("b-only", 42.0, -102.0))
    val c = Seq(entry("dup", 39.9, -99.9, time = 2000L))
    val result = LiveEndpoint.withinBounds(Seq(a, b, c), 30, -120, 50, -80)
    result.map(_.hex).sorted shouldBe Seq("a-only", "b-only", "dup")
    result.find(_.hex == "dup").get.lat shouldBe 40.1
  }

  test("coordinates are rounded to 4 decimals and EDR to 2") {
    LiveEndpoint.round(35.555016, 4) shouldBe 35.5550
    LiveEndpoint.round(-94.996617, 4) shouldBe -94.9966
    LiveEndpoint.round(0.2349, 2) shouldBe 0.23
    LiveEndpoint.round(0.235001, 2) shouldBe 0.24
  }

  private def update(lat: Double, lon: Double, altitudeFt: Int = 37000): LiveFlightUpdate = {
    val ahead = RouteAnalyzer.analyzeRemaining(
      new TurbulenceStore,
      lat,
      lon,
      altitudeFt,
      Some(450),
      Some(270),
      None,
      Instant.parse("2026-10-13T12:00:00Z"),
      AircraftResponse.medium
    )
    LiveFlightUpdate(
      Instant.now(),
      "tracking",
      None,
      AircraftClass.Medium,
      None,
      None,
      usingRoute = false,
      None,
      Some(ahead)
    )
  }

  test("the path ahead is sent first, then left out while the analysis is unchanged") {
    val gate = AheadPointsGate(refreshEvery = 100)
    val first = gate(update(39.0, -98.0)).ahead.get
    first.points should not be empty
    // Points are rounded for the wire.
    first.points.foreach(p => p.lat shouldBe LiveEndpoint.round(p.lat, 4))
    val second = gate(update(39.0, -98.0)).ahead.get
    second.points shouldBe empty
    second.segments should not be empty // the summary still comes with every update
    second.durationMinutes shouldBe first.durationMinutes
    gate(update(39.0, -98.0)).ahead.get.points shouldBe empty
  }

  test("the path ahead is sent again when the analysis changes, and every so often regardless") {
    val gate = AheadPointsGate(refreshEvery = 3)
    gate(update(39.0, -98.0)).ahead.get.points should not be empty
    // A different course ends somewhere else.
    gate(update(39.0, -60.0)).ahead.get.points should not be empty
    val sent = Range.inclusive(1, 7).map(_ => gate(update(39.0, -60.0)).ahead.get.points.nonEmpty)
    sent shouldBe Seq(false, false, false, true, false, false, false)
  }

  test("an update without an analysis passes through, and the next analysis starts over with its path") {
    val gate = AheadPointsGate()
    gate(update(39.0, -98.0))
    val lost = update(39.0, -98.0).copy(ahead = None)
    gate(lost).ahead shouldBe None
    gate(update(39.0, -98.0)).ahead.get.points should not be empty
  }
}
