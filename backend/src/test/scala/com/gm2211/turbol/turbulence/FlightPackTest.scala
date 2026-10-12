/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.endpoints.{PackEndpoint, PackPoint}
import com.gm2211.turbol.util.BaseTest

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream
import scala.collection.immutable.SortedMap

class FlightPackTest extends BaseTest {
  private val (jfkLat, jfkLon) = (40.6413, -73.7781)
  private val (laxLat, laxLon) = (33.9416, -118.4085)
  private val issued = Instant.parse("2026-01-15T12:00:00Z")

  private def emptyFrame(kind: FrameKind, valid: Instant, issuedAt: Instant): EdrFrame =
    EdrFrame(kind, valid, issuedAt, SortedMap.empty)

  private val hourly: Vector[EdrFrame] =
    Range.inclusive(1, 18).map(h =>
      emptyFrame(FrameKind.Forecast, issued.plus(h.toLong, ChronoUnit.HOURS), issued)
    ).toVector

  test("every cell centre maps back to its own cell") {
    val grid = PackGrid(jfkLat, jfkLon, laxLat, laxLon)
    grid.cols shouldBe 81
    grid.rows shouldBe math.ceil(grid.totalKm / 6).toInt + 1
    grid.alongStepKm shouldBe 6.0 +- 0.01
    for {
      row <- Seq(0, 1, grid.rows / 3, grid.rows / 2, grid.rows - 1)
      col <- Seq(0, 1, grid.halfCols, grid.cols - 2, grid.cols - 1)
    } {
      val (lat, lon) = grid.cellCenter(row, col)
      val (along, across) = grid.locate(lat, lon)
      along shouldBe row * grid.alongStepKm +- 1e-6
      across shouldBe (col - grid.halfCols) * grid.crossStepKm +- 1e-6
      grid.cellOf(lat, lon) shouldBe Some((row, col))
    }
    // The centre line is the great circle RouteAnalyzer flies, and the corridor ends 240 km out.
    val (midLat, midLon) = grid.cellCenter(grid.rows / 2, grid.halfCols)
    val (gcLat, gcLon) =
      RouteAnalyzer.intermediate(jfkLat, jfkLon, laxLat, laxLon, (grid.rows / 2) * grid.alongStepKm / grid.totalKm)
    midLat shouldBe gcLat +- 1e-9
    midLon shouldBe gcLon +- 1e-9
    val (edgeLat, edgeLon) = grid.cellCenter(grid.rows / 2, grid.cols - 1)
    RouteAnalyzer.greatCircleKm(midLat, midLon, edgeLat, edgeLon) shouldBe 240.0 +- 1e-6
    grid.cellOf(grid.pointAt(-20, 0)._1, grid.pointAt(-20, 0)._2) shouldBe None
    grid.cellOf(grid.pointAt(100, 260)._1, grid.pointAt(100, 260)._2) shouldBe None
  }

  test("a slab holds the nearest grid point of each cell, and its delta coding round-trips") {
    val grid = PackGrid(jfkLat, jfkLon, laxLat, laxLon)
    val pattern = Array.tabulate[Byte](LambertGrid.nx * LambertGrid.ny)(k =>
      if (k % 101 == 0) EdrLayer.missing else (k % 7 + (k / LambertGrid.nx) % 37).toByte
    )
    val layers = SortedMap.from(FlightPack.levelsFt.map(l => l -> EdrLayer(l, ByteBuffer.wrap(pattern))))
    val frame = EdrFrame(FrameKind.Forecast, issued.plusSeconds(3600), issued, layers)
    val slab = PackSlab(frame, 100, 160)
    val bytes = FlightPack.encode(grid, slab)
    bytes.length shouldBe FlightPack.levelsFt.size * 60 * grid.cols
    val decoded = FlightPack.decode(bytes.clone(), 60, grid.cols)
    for {
      li <- Seq(0, 5, FlightPack.levelsFt.size - 1)
      r <- Seq(0, 1, 30, 59)
      c <- Seq(0, 40, 80)
    } {
      val (lat, lon) = grid.cellCenter(100 + r, c)
      val idx = LambertGrid.nearestIndex(lat, lon)
      val expected = if (idx < 0) EdrLayer.missing else pattern(idx)
      decoded(li * 60 * grid.cols + r * grid.cols + c) shouldBe expected
    }
    val unzipped = new GZIPInputStream(new ByteArrayInputStream(FlightPack.gzip(bytes))).readAllBytes()
    unzipped shouldBe bytes
  }

  test("the slabs cover every point of the flight for takeoffs up to 90 minutes early or late") {
    val grid = PackGrid(jfkLat, jfkLon, laxLat, laxLon)
    val nowcast = emptyFrame(FrameKind.Nowcast, issued.plusSeconds(105 * 60), issued.plusSeconds(105 * 60))
    val data = TurbulenceData(Some(nowcast), hourly)
    val departure = issued.plus(2, ChronoUnit.HOURS)
    val slabs = FlightPack.slabs(grid, data, departure)
    val byFrame = slabs.map(s => s.frame.id -> s).toMap
    val minutesAt = RouteAnalyzer.plannedMinutesAt(grid.totalKm)
    def covered(frame: EdrFrame, row: Int): Boolean =
      byFrame.get(frame.id).exists(s => row >= s.rowStart && row < s.rowEnd)

    for {
      slackMin <- Seq(-90, -45, 0, 45, 90)
      km <- BigDecimal(0) to BigDecimal(grid.totalKm) by 15
    } {
      val takeoff = departure.plusSeconds(slackMin * 60L)
      val time = takeoff.plusSeconds((minutesAt(km.toDouble) * 60).toLong)
      val row = math.rint(km.toDouble / grid.alongStepKm).toInt
      // The frames TurbulenceStore.sampleAt would read at this time.
      withClue(s"nowcast, row $row") {
        (TurbulenceStore.nowcastWeight(nowcast.validTime, time) <= 0 || covered(nowcast, row)) shouldBe true
      }
      val after = hourly.indexWhere(f => !f.validTime.isBefore(time))
      val used =
        if (after == 0) Seq(hourly.head) else if (after < 0) Seq.empty else Seq(hourly(after - 1), hourly(after))
      used.foreach(f => withClue(s"${f.id}, row $row, takeoff $slackMin min") { covered(f, row) shouldBe true })
    }
    // A slab spans only the part of the route the plane can be over while its frame counts.
    val forecastSlabs = slabs.filter(_.frame.kind == FrameKind.Forecast)
    forecastSlabs.head.rowStart shouldBe 0
    forecastSlabs.head.rowEnd should be < grid.rows / 2
    forecastSlabs.last.rowStart should be > grid.rows / 2
    forecastSlabs.last.rowEnd shouldBe grid.rows
  }

  test("JFK to LAX: frames the flight can't use are left out") {
    val grid = PackGrid(jfkLat, jfkLon, laxLat, laxLon)
    val departure = issued.plus(2, ChronoUnit.HOURS)
    val slabs = FlightPack.slabs(grid, TurbulenceData(None, hourly), departure)
    // Departure 14:00Z and ~5h08m gate to gate, plus 90 min of slack and the 1 h interpolation bracket either side.
    slabs.map(_.frame.validTime).min shouldBe issued.plus(1, ChronoUnit.HOURS)
    slabs.map(_.frame.validTime).max shouldBe issued.plus(9, ChronoUnit.HOURS)
    val bytes = slabs.map(s => FlightPack.levelsFt.size.toLong * (s.rowEnd - s.rowStart) * grid.cols).sum
    bytes should be < 4_000_000L
  }

  test("pack URLs are stable: coordinates are rounded and printed the same way") {
    PackEndpoint.parsePoint("40.64131,-73.77814") shouldBe Some(PackPoint(40.6413, -73.7781))
    PackEndpoint.parsePoint("91,0") shouldBe None
    PackEndpoint.parsePoint("nope") shouldBe None
    PackEndpoint.formatPoint(40.6413, -73.7781) shouldBe "40.6413,-73.7781"
    PackEndpoint.parseInstant("2026-01-15T12:00:00Z") shouldBe Some(issued)
    PackEndpoint.parseInstant(issued.getEpochSecond.toString) shouldBe Some(issued)
    val grid = PackGrid(jfkLat, jfkLon, laxLat, laxLon)
    val manifest = PackEndpoint.manifest(
      grid,
      TurbulenceData(None, hourly),
      PackPoint(jfkLat, jfkLon),
      PackPoint(laxLat, laxLon),
      issued.plus(2, ChronoUnit.HOURS),
      issued
    )
    manifest.frames.head.url shouldBe
      s"/api/packs/slab/${manifest.frames.head.id}?o=40.6413,-73.7781&d=33.9416,-118.4085" +
      s"&rows=${manifest.frames.head.rowStart}-${manifest.frames.head.rowEnd}&v=1"
  }
}
