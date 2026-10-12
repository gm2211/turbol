/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.nio.file.{Files, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.math.*

class GlobalLayerTest extends BaseTest {
  import TurbulenceTestData.*

  private val t0 = Instant.parse("2026-10-12T00:00:00Z")

  test("global grid indices wrap around the date line") {
    GlobalGrid.nearestIndex(90, 0) shouldBe 0
    GlobalGrid.nearestIndex(-90, 0) shouldBe (GlobalGrid.ny - 1) * GlobalGrid.nx
    GlobalGrid.nearestIndex(51.5, -0.12) shouldBe GlobalGrid.rowAt(51.5) * GlobalGrid.nx
    GlobalGrid.nearestIndex(0, -30) shouldBe 360 * GlobalGrid.nx + 1320
    GlobalGrid.nearestIndex(0, 330) shouldBe GlobalGrid.nearestIndex(0, -30)
    GlobalGrid.nearestIndex(0, 179.99) shouldBe GlobalGrid.nearestIndex(0, -180)
  }

  test("TI1 maps to EDR through the fitted table: knots exactly, monotone between, extended above") {
    val table = GfsTurbulence.table
    table.levelsFt.indices.foreach { k =>
      table.ti1(k).zip(table.edr(k)).foreach { case (x, y) => GfsTurbulence.edrFromTi1(k, x) shouldBe y +- 1e-9 }
      val xs = (0 until 200).map(n => table.ti1(k).head * pow(1.05, n))
      val ys = xs.map(GfsTurbulence.edrFromTi1(k, _))
      ys.zip(ys.tail).foreach { case (a, b) => b should be >= a }
      GfsTurbulence.edrFromTi1(k, table.ti1(k).last * 4) should be > table.edr(k).last
      GfsTurbulence.edrFromTi1(k, 1.0) should be <= 1.0
    }
  }

  test("deformation of a pure northward shear u = k * y is k") {
    val k = 2e-5
    val earthRadius = 6371229.0
    val u = Array.tabulate(GlobalGrid.nx * GlobalGrid.ny)(p =>
      (k * earthRadius * toRadians(GlobalGrid.latOf(p / GlobalGrid.nx))).toFloat
    )
    val v = new Array[Float](u.length)
    val deform = GfsTurbulence.deformation(u, v)
    Seq(
      -60.0, -20.0, 0.0, 35.0, 60.0
    ).foreach(lat => deform(GlobalGrid.nearestIndex(lat, 10)).toDouble shouldBe k +- 1e-9)
  }

  test("outside GTG the global layer answers, inside GTG wins, and the edge is a blend") {
    val store = new TurbulenceStore
    store.setForecast(Vector(uniformFrame(FrameKind.Forecast, t0, Map(35000 -> 0.02))))
    store.setGlobal(
      Vector(0, 3).map(h =>
        uniformFrame(FrameKind.Global, t0.plus(h.toLong, ChronoUnit.HOURS), Map(35000 -> (0.10 + h * 0.05)))
      )
    )
    val london = store.sample(51.5, -0.1, 35000, t0)
    london.source shouldBe EdrSource.Global
    london.edr shouldBe 0.10 +- 0.003
    // Halfway between the 0 h (0.10) and 3 h (0.25) global frames.
    store.sample(51.5, -0.1, 35000, t0.plus(90, ChronoUnit.MINUTES)).edr shouldBe 0.175 +- 0.003
    val kansas = store.sample(39, -98, 35000, t0)
    kansas.source shouldBe EdrSource.Forecast
    kansas.edr shouldBe 0.02 +- 0.003
    // A point 10 cells inside GTG's east edge: GTG weight 0.25.
    val (lat, lon) = LambertGrid.latLonAt(LambertGrid.nx - 11, LambertGrid.ny / 2)
    val edge = store.sample(lat, lon, 35000, t0)
    edge.edr shouldBe (0.25 * 0.02 + 0.75 * 0.10) +- 0.003
    edge.source shouldBe EdrSource.Global
    // Global frames don't stretch beyond 3 hours past the last one.
    store.sample(51.5, -0.1, 35000, t0.plus(7, ChronoUnit.HOURS)).source shouldBe EdrSource.NoData
  }

  test("3D volume adds coarse global blocks outside GTG only") {
    val gtg = uniformFrame(FrameKind.Nowcast, t0, Map(35000 -> 0.02))
    val global = uniformFrame(FrameKind.Global, t0, Map(35000 -> 0.20))
    val volume = VolumeSampler().volume(gtg, Some(global), 8)
    volume.voxels shouldBe empty
    volume.globalLevelsFt shouldBe Vector(35000)
    volume.globalCellKm shouldBe 168.0
    val voxels = volume.globalVoxels
    voxels.length / 4 should be > 5000
    (0 until voxels.length by 4).foreach { k =>
      val lat = voxels(k) / 100.0
      val lon = voxels(k + 1) / 100.0
      LambertGrid.nearestIndex(lat, lon) shouldBe -1
      math.abs(lat) should be <= 80.0
      EdrLayer.decode(voxels(k + 3).toByte) shouldBe 0.20 +- 0.003
    }
  }

  test("the volume JSON written by hand parses back to the same volume") {
    val gtg = uniformFrame(FrameKind.Nowcast, t0, Map(35000 -> 0.20))
    val global = uniformFrame(FrameKind.Global, t0, Map(35000 -> 0.20))
    val volume = VolumeSampler().volume(gtg, Some(global), 8)
    val json = io.circe.parser.parse(volume.toJsonString).toOption.get.hcursor
    json.get[Vector[Int]]("levelsFt") shouldBe Right(volume.levelsFt)
    json.get[Int]("step") shouldBe Right(8)
    json.get[Double]("cellKm") shouldBe Right(volume.cellKm)
    def sameInts(name: String, expected: Array[Int]) = {
      val parsed = json.get[Vector[Int]](name).toOption.get
      parsed.length shouldBe expected.length
      parsed.indices.find(k => parsed(k) != expected(k)) shouldBe None
    }
    sameInts("voxels", volume.voxels)
    json.get[Vector[Int]]("globalLevelsFt") shouldBe Right(volume.globalLevelsFt)
    json.get[Double]("globalCellKm") shouldBe Right(volume.globalCellKm)
    sameInts("globalVoxels", volume.globalVoxels)
    volume.voxels.length should be > 0
  }

  // Decodes a real GFS file when one is around (data-pipeline/gfs_turbulence.py collect downloads it). Expected values
  // are the pipeline's own output for the same file (gfs_turbulence.gfs_edr), so the Scala port must match it.
  test("decodes a real GFS file the same way as the pipeline") {
    val cwd = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    val sample = Seq(cwd, cwd.getParent)
      .map(_.resolve("data-pipeline/data/gfs_cal/gfs.2026101118.f006.grib2"))
      .find(Files.exists(_))
    assume(sample.isDefined, "no GFS sample file")
    val start = System.nanoTime()
    val layers = GfsTurbulence.decodeToLayers(sample.get, Files.createTempDirectory("gfs-test"))
    info(f"decode: ${(System.nanoTime() - start) / 1e9}%.1f s")
    layers.keys.toVector shouldBe GfsTurbulence.levelsFt
    val frame = EdrFrame(FrameKind.Global, t0, t0, layers)
    // (lat, lon, EDR at 10,000 / 30,000 / 35,000 / 39,000 ft) from data-pipeline
    Seq(
      (51.5, -30.0, Seq(0.0579, 0.1767, 0.1220, 0.1423)),
      (45.0, -40.0, Seq(0.0533, 0.0393, 0.1109, 0.1081)),
      (40.6, -73.8, Seq(0.0988, 0.0955, 0.0624, 0.0746)),
      (55.0, 10.0, Seq(0.0564, 0.0606, 0.0462, 0.0516)),
      (30.0, 140.0, Seq(0.0459, 0.0572, 0.0631, 0.0771)),
      (-35.0, 150.0, Seq(0.1164, 0.0882, 0.1143, 0.0936)),
      (60.0, -20.0, Seq(0.1026, 0.0740, 0.0901, 0.0649)),
      (48.0, -122.0, Seq(0.1174, 0.1323, 0.1433, 0.1429))
    ).foreach { case (lat, lon, expected) =>
      Seq(10000, 30000, 35000, 39000).zip(expected).foreach { case (ft, edr) =>
        withClue(s"$lat,$lon at $ft ft: ") { frame.edrAt(lat, lon, ft) shouldBe edr +- 0.006 }
      }
    }
  }

  test("WAFS levels bracket GTG altitudes and the blend rises with either input") {
    Wafs.bracket(30000) shouldBe ((Wafs.table.levelsFl.indexOf(300), 0.0))
    val (k, t) = Wafs.bracket(35000)
    Wafs.table.levelsFl(k) shouldBe 340
    t shouldBe 0.2 +- 1e-9
    Wafs.bracket(10000) shouldBe ((0, 0.0))
    Wafs.blendLevel(10000) shouldBe None
    val level = Wafs.blendLevel(35000).get
    val grid = Range.inclusive(0, 60).map(_ * 0.01)
    grid.zip(grid.tail).foreach { case (a, b) =>
      Wafs.blend(level, b, 0.1) should be >= Wafs.blend(level, a, 0.1)
      Wafs.blend(level, 0.1, b) should be >= Wafs.blend(level, 0.1, a)
    }
    Wafs.blend(level, 2.0, 2.0) should be <= 1.0
  }

  test("a WAFS image decodes to the fitted EDR of its ramp colour, nothing poleward of 76 degrees") {
    import java.awt.image.BufferedImage
    val image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
    val orange = Wafs.table.ramp(9) // step 10
    for {
      x <- Range(0, 450)
      y <- Range(0, 600)
    } image.setRGB(x, y, (0xff << 24) | (orange(0) << 16) | (orange(1) << 8) | orange(2))
    val out = new java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", out)
    val layer = Wafs.decodePng(out.toByteArray)
    EdrLayer.decode(layer(GlobalGrid.nearestIndex(40, -100))) shouldBe Wafs.table.stepEdr(10) +- 0.003
    EdrLayer.decode(layer(GlobalGrid.nearestIndex(40, 100))) shouldBe Wafs.table.stepEdr(0) +- 0.003
    EdrLayer.decode(layer(GlobalGrid.nearestIndex(80, -100))).isNaN shouldBe true
  }

  // Same as above with the WAFS images of the same cycle blended in (data-pipeline/wafs_blend.py collect).
  test("blends real WAFS images the same way as the pipeline") {
    val cwd = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    val dir = Seq(cwd, cwd.getParent).map(_.resolve("data-pipeline/data/gfs_cal")).find(Files.isDirectory(_))
    val grib = dir.map(_.resolve("gfs.2026101118.f006.grib2")).filter(Files.exists(_))
    val pngs = dir.map(d => Wafs.table.levelsHpa.map(hPa => d.resolve(s"wafs/2026101118_$hPa.png")))
    assume(grib.isDefined && pngs.exists(_.forall(Files.exists(_))), "no GFS/WAFS sample files")
    val wafs = pngs.get.map(p => Wafs.decodePng(Files.readAllBytes(p)))
    val layers = GfsTurbulence.decodeToLayers(grib.get, Files.createTempDirectory("wafs-test"), Some(wafs))
    val frame = EdrFrame(FrameKind.Global, t0, t0, layers)
    // (lat, lon, EDR at 20,000 / 30,000 / 35,000 / 39,000 ft) from data-pipeline
    Seq(
      (51.5, -30.0, Seq(0.1170, 0.1990, 0.1234, 0.1497)),
      (45.0, -40.0, Seq(0.1032, 0.0393, 0.0925, 0.0945)),
      (55.0, 10.0, Seq(0.0802, 0.0602, 0.0459, 0.0514)),
      (30.0, 140.0, Seq(0.0542, 0.0569, 0.0618, 0.0748)),
      (-35.0, 150.0, Seq(0.1732, 0.1348, 0.1173, 0.1129)),
      (60.0, -20.0, Seq(0.1408, 0.1076, 0.0818, 0.0640))
    ).foreach { case (lat, lon, expected) =>
      Seq(20000, 30000, 35000, 39000).zip(expected).foreach { case (ft, edr) =>
        withClue(s"$lat,$lon at $ft ft: ") { frame.edrAt(lat, lon, ft) shouldBe edr +- 0.008 }
      }
    }
  }

  test("the throttle keeps busy time to its duty share of wall-clock time") {
    def run(throttle: Throttle): (Long, Long) = {
      val start = System.nanoTime()
      var busy = 0L
      while (busy < 300000000L) { // 300 ms of work, in ~1 ms pieces
        val t = System.nanoTime()
        while (System.nanoTime() - t < 1000000L) {}
        busy += System.nanoTime() - t
        throttle.tick()
      }
      (busy, System.nanoTime() - start)
    }
    val (busy, wall) = run(new Throttle(0.5, sliceMillis = 20))
    (busy.toDouble / wall) should be < 0.6
    val (offBusy, offWall) = run(Throttle.off)
    (offBusy.toDouble / offWall) should be > 0.9
  }
}
