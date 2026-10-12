/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.turbol.turbulence.{AircraftResponse, PathPoint, RouteAnalyzer, TurbulenceStore}
import com.gm2211.turbol.util.BaseTest

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.time.Instant
import java.util.zip.GZIPOutputStream

class FlightHistoryTest extends BaseTest {
  private def quad(a: Int, b: Int, c: Int, d: Int): Array[Byte] =
    ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(a).putInt(b).putInt(c).putInt(d).array()

  private def callsignRecord(hex: Int, callsign: String): Array[Byte] = {
    val padded = String.format("%-8s", callsign).getBytes("US-ASCII")
    val b = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
    quad(hex, 0x40000110, b.getInt(0), b.getInt(4))
  }

  test("reads callsign -> airframe from a gzipped tar1090 heatmap slot") {
    val records = List(
      quad(0x0e7f7c9d, 0x1a1, 0x28e72780, 0x2710), // slice header
      quad(0xab772d, (40.64 * 1e6).toInt, (-73.78 * 1e6).toInt, 0x10af0618), // a position
      callsignRecord(0xab772d, "DAL184"),
      callsignRecord(0x8963f4, "UAE5RQ")
    )
    val out = new ByteArrayOutputStream()
    val gz = new GZIPOutputStream(out)
    records.foreach(r => gz.write(r, 0, r.length))
    gz.close()
    val index = FlightHistory.parseSlot(new ByteArrayInputStream(out.toByteArray))
    index.size shouldBe 2
    index.hex("DAL184") shouldBe Some("ab772d")
    index.hex("dal184 ") shouldBe Some("ab772d")
    index.hex("UAE5RQ") shouldBe Some("8963f4")
    index.hex("AAL198") shouldBe None
  }

  // Shaped like adsb.lol's trace_full: [seconds after timestamp, lat, lon, alt or "ground", gs, track, flags, rate, details].
  private val trace =
    """{"icao":"ab772d","r":"N838MH","t":"B764","timestamp": 1791676800.0,"trace":[
      |[0.0,40.64,-73.78,"ground",2.8,303.8,0,null,{"flight":"DAL184  "}],
      |[60.0,40.65,-73.80,500,150,40,0,1500,null],
      |[600.0,41.20,-72.50,12000,300,60,0,1500,null],
      |[1200.0,42.00,-70.00,35000,480,60,0,0,null],
      |[16000.0,45.00,5.00,35000,480,100,0,0,null],
      |[24000.0,45.59,8.74,1300,140,350,0,-800,null],
      |[24100.0,45.63,8.72,"ground",20,350,0,null,null],
      |[30000.0,45.66,8.71,2150,160,170,2,1500,{"flight":"DAL185"}],
      |[30300.0,45.30,8.00,15000,300,250,0,1500,null],
      |[30600.0,45.00,7.00,25000,400,250,0,1500,null],
      |[30900.0,44.50,5.00,35000,480,250,0,1500,null],
      |[31200.0,44.00,3.00,35000,480,250,0,0,null]
      |]}""".stripMargin

  private val jfk = Place("JFK", "John F. Kennedy", 40.6398, -73.7789, Some("KJFK"))
  private val mxp = Place("MXP", "Malpensa", 45.6306, 8.7281, Some("LIMC"))

  test("cuts an airframe's trace into legs at landings and new-leg flags") {
    val legs = FlightHistory.parseLegs(trace)
    legs.map(_.callsign) shouldBe Seq("DAL184", "DAL185")
    val leg = legs.head
    leg.typeCode shouldBe Some("B764")
    leg.points.size shouldBe 5
    leg.takeoff shouldBe Instant.ofEpochSecond(1791676860L)
    leg.landing shouldBe Instant.ofEpochSecond(1791700800L)
    leg.flies(jfk, mxp) shouldBe true
    leg.flies(mxp, jfk) shouldBe false
  }

  test("finds the route's callsigns as consecutive stops in the route directory") {
    RouteDirectory.parse("UAE206,UAE,206,UAE,KJFK-LIMC-OMDB") shouldBe Some("UAE206" -> Seq("KJFK", "LIMC", "OMDB"))
    RouteDirectory.hasLeg(Seq("KJFK", "LIMC", "OMDB"), "KJFK", "LIMC") shouldBe true
    RouteDirectory.hasLeg(Seq("KJFK", "LIMC", "OMDB"), "KJFK", "OMDB") shouldBe false
    RouteDirectory.hasLeg(Seq("LIMC", "KJFK"), "KJFK", "LIMC") shouldBe false
  }

  test("moves an observed takeoff to the same time of day on the requested day") {
    val from = Instant.parse("2026-10-14T04:00:00Z") // midnight in New York
    val until = Instant.parse("2026-10-15T04:00:00Z")
    RouteScheduleService.onDay(Instant.parse("2026-10-11T02:51:00Z"), from, until) shouldBe
      Some(Instant.parse("2026-10-15T02:51:00Z"))
    RouteScheduleService.onDay(Instant.parse("2026-10-11T22:00:00Z"), from, until) shouldBe
      Some(Instant.parse("2026-10-14T22:00:00Z"))
    RouteScheduleService.onDay(Instant.parse("2026-10-11T22:00:00Z"), from, from.plusSeconds(3600)) shouldBe None
  }

  test("scans the last day's hourly slots, skipping the ones still being written") {
    val slots = FlightHistory.slotsToScan(Instant.parse("2026-10-12T14:40:00Z"))
    slots.size shouldBe 24
    slots.head shouldBe Instant.parse("2026-10-12T13:00:00Z")
    slots.last shouldBe Instant.parse("2026-10-11T14:00:00Z")
  }

  test("analyzes along a flown path, keeping its altitudes and timing") {
    val leg = FlightHistory.parseLegs(trace).head
    val path = leg.points.map(p =>
      PathPoint(java.time.Duration.between(leg.takeoff, p.time).getSeconds / 60.0, p.lat, p.lon, p.altitudeFt)
    )
    val departure = Instant.parse("2026-10-14T02:51:00Z")
    val a = RouteAnalyzer.analyzePath(TurbulenceStore(), path, departure, AircraftResponse.fromAdsb(None, Some("B764")))
    a.cruiseAltitudeFt shouldBe 35000
    a.points.head.lat shouldBe 40.65 +- 1e-6
    a.points.last.lat shouldBe 45.59 +- 1e-6
    a.durationMinutes shouldBe path.last.minutes +- 1e-6
    a.points.head.time shouldBe departure
    a.points.map(_.altitudeFt).max shouldBe 35000
  }
}
