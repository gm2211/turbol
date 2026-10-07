/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

class TurbulenceTest extends BaseTest {
  import TurbulenceTestData.*

  // Reference indices from pyproj on the same grid (data-pipeline/gtgn_common.latlon_to_ij).
  private val reference = Seq(
    (40.6413, -73.7781, 1250065), // JFK
    (33.9416, -118.4085, 779227), // LAX
    (39.0, -98.0, 986737),
    (47.6, -122.3, 1712927),
    (25.8, -80.3, 197574),
    (45.0, -95.0, 1387994)
  )

  test("Lambert projection matches pyproj grid indices") {
    reference.foreach { case (lat, lon, expected) => LambertGrid.nearestIndex(lat, lon) shouldBe expected }
  }

  test("grid coordinates map back to the lat/lon they came from") {
    reference.foreach { case (lat, lon, _) =>
      val (fi, fj) = LambertGrid.fractionalIndex(lat, lon)
      val (lat2, lon2) = LambertGrid.latLonAt(fi, fj)
      lat2 shouldBe lat +- 1e-6
      lon2 shouldBe lon +- 1e-6
    }
  }

  test("3D volume drops lone rough points but always shows severe ones") {
    val frame = uniformFrame(FrameKind.Nowcast, Instant.EPOCH, Map(30000 -> 0.05, 35000 -> 0.05))
    val index = LambertGrid.nearestIndex(39.0, -98.0)
    frame.levels(30000).data.put(index, EdrLayer.encode(0.30f)) // Moderate, 1 of 64 points: hidden
    frame.levels(35000).data.put(index, EdrLayer.encode(0.50f)) // Severe: always shown
    val volume = VolumeSampler().volume("test", frame, 8)
    volume.voxels.length shouldBe 4
    volume.voxels(0) / 100.0 shouldBe 39.0 +- 0.2
    volume.voxels(1) / 100.0 shouldBe -98.0 +- 0.2
    volume.levelsFt(volume.voxels(2)) shouldBe 35000
    EdrLayer.decode(volume.voxels(3).toByte) shouldBe 0.50 +- 0.003
  }

  test("3D volume shows a block whose rough patch covers a quarter of it") {
    val frame = uniformFrame(FrameKind.Nowcast, Instant.EPOCH, Map(35000 -> 0.05))
    val (fi, fj) = LambertGrid.fractionalIndex(39.0, -98.0)
    val (i0, j0) = ((fi.toInt / 8) * 8, (fj.toInt / 8) * 8)
    for {
      j <- j0 until j0 + 4
      i <- i0 until i0 + 4
    } // 16 of the block's 64 points
      frame.levels(35000).data.put(j * LambertGrid.nx + i, EdrLayer.encode(0.30f))
    val volume = VolumeSampler().volume("test", frame, 8)
    volume.voxels.length shouldBe 4
    EdrLayer.decode(volume.voxels(3).toByte) shouldBe 0.30 +- 0.003
  }

  test("locations outside the grid have no index") {
    LambertGrid.nearestIndex(51.47, -0.45) shouldBe -1 // London
    LambertGrid.nearestIndex(-33.9, 151.2) shouldBe -1 // Sydney
  }

  test("EDR byte encoding round-trips at 0.005 resolution and keeps missing values") {
    EdrLayer.decode(EdrLayer.encode(0.12f)) shouldBe 0.12 +- 0.0026
    EdrLayer.decode(EdrLayer.encode(9999f)).isNaN shouldBe true
    EdrLayer.decode(EdrLayer.encode(Float.NaN)).isNaN shouldBe true
  }

  test("frames interpolate linearly between levels") {
    val frame = uniformFrame(FrameKind.Nowcast, Instant.EPOCH, Map(30000 -> 0.10, 40000 -> 0.20))
    frame.edrAt(39.0, -98.0, 35000) shouldBe 0.15 +- 0.001
    frame.edrAt(39.0, -98.0, 45000) shouldBe 0.20 +- 0.001
  }

  test("nowcast dominates at its valid time and hands over to the forecast within an hour") {
    val t0 = Instant.parse("2026-10-07T00:00:00Z")
    val store = new TurbulenceStore
    store.setNowcast(uniformFrame(FrameKind.Nowcast, t0, Map(35000 -> 0.30)))
    store.setForecast(
      Range.inclusive(1, 3)
        .map(h => uniformFrame(FrameKind.Forecast, t0.plus(h.toLong, ChronoUnit.HOURS), Map(35000 -> 0.10 * h)))
        .toVector
    )
    store.sample(39, -98, 35000, t0).edr shouldBe 0.30 +- 0.003
    store.sample(39, -98, 35000, t0).source shouldBe EdrSource.Nowcast
    store.sample(39, -98, 35000, t0.plus(30, ChronoUnit.MINUTES)).source shouldBe EdrSource.Blend
    // 90 min: pure forecast, halfway between F001 (0.1) and F002 (0.2)
    store.sample(39, -98, 35000, t0.plus(90, ChronoUnit.MINUTES)).edr shouldBe 0.15 +- 0.003
    store.sample(39, -98, 35000, t0.plus(5, ChronoUnit.HOURS)).source shouldBe EdrSource.NoData
  }

  // Decodes a real NOMADS file when one is around (data-pipeline/fetch.py downloads it); values cross-checked with
  // eccodes in data-pipeline: EDR at FL350 over JFK/LAX/central US for gtgn.20261007 t0030z.
  test("decodes a real GTGN file") {
    val cwd = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    val dir =
      Seq(cwd, cwd.getParent).map(_.resolve("data-pipeline").resolve("data")).find(Files.isDirectory(_)).getOrElse(cwd)
    val sample: Option[Path] =
      if (!Files.isDirectory(dir)) None
      else Files.list(dir).iterator().asScala.find(_.getFileName.toString.matches("gtgn\\.t\\d{4}z\\.3km\\.grib2"))
    assume(sample.isDefined, s"no GTGN sample file in $dir")
    val out = Files.createTempDirectory("gtgn-test")
    val layers = GribDecoder.decodeToLayers(sample.get, out)
    layers.size shouldBe 51
    layers.keys.head shouldBe 100
    layers.keys.last shouldBe 50000
    val fl350 = layers(35000)
    val values: Seq[Double] = reference.map { case (_, _, idx) => fl350.edrAtIndex(idx) }
    values.count(_.isNaN) shouldBe 0
    if (sample.get.getFileName.toString == "gtgn.t0030z.3km.grib2") {
      values.zip(Seq(0.09, 0.07, 0.06, 0.06, 0.17, 0.11)).foreach { case (v, expected) => v shouldBe expected +- 0.003 }
    }
  }
}
