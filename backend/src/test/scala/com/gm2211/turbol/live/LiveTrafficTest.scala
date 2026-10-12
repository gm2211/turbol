/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.turbol.turbulence.AircraftClass
import com.gm2211.turbol.util.BaseTest

class LiveTrafficTest extends BaseTest {
  // Trimmed from a real api.adsb.lol/v2 response.
  private val adsbLol =
    """{"ac":[
      |{"hex":"a12ac7","flight":"UAL1517 ","r":"N17427","t":"B39M","alt_baro":39000,"gs":485.8,"track":36.21,
      | "baro_rate":0,"category":"A3","lat":35.555016,"lon":-94.996617,"seen_pos":0.2,"seen":0.0},
      |{"hex":"a00001","flight":"N1     ","alt_baro":"ground","lat":40.0,"lon":-75.0,"category":"A1"},
      |{"hex":"a00002","flight":"NOPOS  "}
      |],"msg":"No error","now":1791334006500,"total":3}""".stripMargin

  test("parses adsb.lol aircraft, keeping ground traffic flagged and dropping position-less entries") {
    val (now, aircraft) = LiveTrafficClient.parseAircraftList(adsbLol)
    now.toEpochMilli shouldBe 1791334006500L
    aircraft.map(_.hex) shouldBe Seq("a12ac7", "a00001")
    val ual = aircraft.head
    ual.callsign shouldBe Some("UAL1517")
    ual.altitudeFt shouldBe Some(39000)
    ual.onGround shouldBe false
    aircraft(1).onGround shouldBe true
    aircraft(1).altitudeFt shouldBe None
  }

  test("maps ADS-B categories and types to weight classes") {
    AircraftClass.fromAdsb(Some("A5"), Some("B77W")) shouldBe AircraftClass.Heavy
    AircraftClass.fromAdsb(Some("A3"), Some("B788")) shouldBe AircraftClass.Heavy
    AircraftClass.fromAdsb(Some("A3"), Some("B38M")) shouldBe AircraftClass.Medium
    AircraftClass.fromAdsb(None, Some("B737")) shouldBe AircraftClass.Medium
    AircraftClass.fromAdsb(None, Some("C25A")) shouldBe AircraftClass.Light
    AircraftClass.fromAdsb(None, Some("b789")) shouldBe AircraftClass.Heavy
    AircraftClass.fromAdsb(None, None) shouldBe AircraftClass.Medium
    AircraftClass.fromAdsb(Some("A1"), Some("C172")) shouldBe AircraftClass.Light
  }

  test("covers a viewport with 250 nm circles that leave no gaps") {
    val cells = LiveTrafficService.cellsCovering(24, -125, 50, -66)
    cells.size should be > 30
    cells.size should be <= 120
    // every point of the box is within 250 nm (463 km) of some cell centre
    for {
      lat <- Range.inclusive(24, 50, 2)
      lon <- Range.inclusive(-125, -66, 3)
    } {
      val nearestKm = cells.map(c => haversineKm(lat, lon, c.lat, c.lon)).min
      nearestKm should be < 463.0
    }
  }

  test("a view too wide for the cell budget keeps the cells around its centre") {
    val cells = LiveTrafficService.cellsCovering(-60, -180, 80, 180) // most of the world
    cells.size shouldBe 120
    cells.exists(c => math.abs(c.lat - 7.5) < 3 && math.abs(c.lon) < 10) shouldBe true // centre is covered
    cells.map(_.lat).max should be > 20.0 // not just the southern rows
  }

  test("dead-reckons stale positions along the track") {
    val a = LiveTrafficClient.parseAircraftList(adsbLol)._2.head.copy(lat = 40.0, lon = -100.0, trackDeg = Some(90.0))
    val moved = LiveTrafficService.extrapolate(a, 60) // 485.8 kt for a minute is ~15 km east
    moved.lat shouldBe 40.0 +- 0.01
    haversineKm(40.0, -100.0, moved.lat, moved.lon) shouldBe 15.0 +- 0.2
    LiveTrafficService.extrapolate(a, 600) shouldBe a // too old to guess
  }

  private def haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = {
    val dLat = math.toRadians(lat2 - lat1)
    val dLon = math.toRadians(lon2 - lon1)
    val a = math.pow(math.sin(dLat / 2), 2) +
      math.cos(math.toRadians(lat1)) * math.cos(math.toRadians(lat2)) * math.pow(math.sin(dLon / 2), 2)
    2 * 6371 * math.asin(math.sqrt(a))
  }

  test("matches a live aircraft to its route only when it is flying that leg") {
    val mdwSrq = FlightRoute(
      "SWA2767",
      Some("WN2767"),
      Some("Southwest Airlines"),
      Place("MDW", "Chicago Midway", 41.786, -87.7524),
      Place("SRQ", "Sarasota Bradenton", 27.3954, -82.5544)
    )
    def at(lat: Double, lon: Double, track: Double) =
      LiveAircraft(
        "abb1de",
        Some("SWA2767"),
        None,
        None,
        None,
        lat,
        lon,
        Some(35000),
        false,
        None,
        Some(track),
        None,
        0
      )
    // Real adsb.lol report from 2026-10-11 22:41Z: same callsign, near the MDW-SRQ line, but heading NE on an
    // earlier leg while MDW→SRQ had not departed yet.
    mdwSrq.isFlownBy(at(37.956693, -87.568678, 58.89)) shouldBe false
    mdwSrq.isFlownBy(at(37.956693, -87.568678, 160)) shouldBe true
    mdwSrq.isFlownBy(at(36.0, -100.0, 160)) shouldBe false // far off the route
    mdwSrq.isFlownBy(at(27.6, -82.4, 0)) shouldBe true // on approach, any heading
  }
}
