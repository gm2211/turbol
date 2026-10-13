/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Instant
import javax.imageio.ImageIO
import scala.collection.immutable.SortedMap
import scala.math.*

class TileRendererTest extends BaseTest {
  // Every grid point gets a different value (mod 255), so a pixel's colour pins down which point it sampled.
  private val layer =
    EdrLayer(35000, ByteBuffer.wrap(Array.tabulate(LambertGrid.nx * LambertGrid.ny)(i => (i % 255).toByte)))
  private val renderer = TileRenderer()
  private val frame = EdrFrame(FrameKind.Nowcast, Instant.EPOCH, Instant.EPOCH, SortedMap(35000 -> layer))

  private def pixels(png: Array[Byte]): Vector[Vector[Int]] = {
    val image = ImageIO.read(ByteArrayInputStream(png))
    Vector.tabulate(256)(py => Vector.tabulate(256)(px => image.getRGB(px, py)))
  }

  test("the split projection picks the same grid point as the full one") {
    for {
      lat <- Range.BigDecimal(21.5, 52.5, 0.37).map(_.toDouble)
      lon <- Range.BigDecimal(-133.5, -61.0, 0.53).map(_.toDouble)
    } {
      val theta = LambertGrid.thetaAt(lon)
      LambertGrid.nearestIndex(LambertGrid.rhoAt(lat), sin(theta), cos(theta)) shouldBe
        LambertGrid.nearestIndex(lat, lon)
    }
  }

  test("tiles colour each pixel from its nearest grid point") {
    val (z, x, y) = (5, 7, 12)
    val image = pixels(renderer.render(frame, None, 35000, z, x, y))
    val worldPx = 256.0 * (1 << z)
    for {
      py <- 0 until 256 by 17
      px <- 0 until 256 by 13
    } {
      val lat = toDegrees(atan(sinh(Pi * (1 - 2 * (y * 256 + py + 0.5) / worldPx))))
      val lon = (x * 256 + px + 0.5) / worldPx * 360.0 - 180.0
      val index = LambertGrid.nearestIndex(lat, lon)
      image(py)(px) shouldBe renderer.colour(layer.edrAtIndex(index))
    }
  }

  test("tiles outside the grid are transparent and not cached per tile") {
    val png = renderer.render(frame, None, 35000, 5, 20, 12) // over Asia
    pixels(png).forall(_.forall(c => (c >>> 24) == 0)) shouldBe true
    renderer.render(frame, None, 35000, 5, 21, 12) should be theSameInstanceAs png
  }

  test("outside GTG the global layer fills in, blended across GTG's edge") {
    val global = TurbulenceTestData.uniformFrame(FrameKind.Global, Instant.EPOCH, Map(35000 -> 0.30))
    val gtg = TurbulenceTestData.uniformFrame(FrameKind.Nowcast, Instant.EPOCH, Map(35000 -> 0.02))
    val (z, x, y) = (4, 4, 5) // western Atlantic: GTG's east edge runs through it
    val image = pixels(renderer.render(gtg, Some(global), 35000, z, x, y))
    val worldPx = 256.0 * (1 << z)
    var seen = Set.empty[String]
    for {
      py <- 0 until 256 by 7
      px <- 0 until 256 by 7
    } {
      val lat = toDegrees(atan(sinh(Pi * (1 - 2 * (y * 256 + py + 0.5) / worldPx))))
      val lon = (x * 256 + px + 0.5) / worldPx * 360.0 - 180.0
      val inGrid = LambertGrid.nearestIndex(lat, lon) >= 0
      val (fi, fj) = LambertGrid.fractionalIndex(lat, lon)
      val w = if (inGrid) LambertGrid.interiorWeight(fi, fj) else 0.0
      val expected = EdrLayer.decode(EdrLayer.encode((w * 0.02 + (1 - w) * 0.30).toFloat))
      image(py)(px) shouldBe renderer.colour(expected)
      seen += (if (w >= 1) "gtg" else if (w <= 0) "global" else "blend")
    }
    seen shouldBe Set("gtg", "global", "blend")
  }

  test("with a global layer, tiles anywhere in the world have data") {
    val global = TurbulenceTestData.uniformFrame(FrameKind.Global, Instant.EPOCH, Map(35000 -> 0.30))
    val image = pixels(renderer.render(frame, Some(global), 35000, 5, 20, 12)) // over Asia
    image.flatMap(identity).distinct shouldBe Vector(renderer.colour(EdrLayer.decode(EdrLayer.encode(0.30f))))
  }

  test("prewarming renders the opening view quickly") {
    TileRenderer().prewarm(frame, None) // JIT warm-up
    val fresh = TileRenderer()
    val start = System.nanoTime()
    fresh.prewarm(frame, None)
    val ms = (System.nanoTime() - start) / 1e6
    info(f"prewarm: $ms%.0f ms")
    ms should be < 2000.0
  }

  test("a tile whose global layer changed is still there, as last drawn, until it's redrawn") {
    val global = EdrFrame(FrameKind.Global, Instant.EPOCH, Instant.EPOCH, SortedMap(35000 -> layer))
    val (z, x, y) = (5, 7, 12)
    val png = renderer.render(frame, None, 35000, z, x, y)
    renderer.cached(frame, Some(global), 35000, z, x, y) shouldBe None
    renderer.previous(frame, 35000, z, x, y) shouldBe Some(png)
    val redrawn = renderer.render(frame, Some(global), 35000, z, x, y)
    renderer.previous(frame, 35000, z, x, y) shouldBe Some(redrawn)
  }
}
