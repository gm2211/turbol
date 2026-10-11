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
  private val layer = EdrLayer(35000, ByteBuffer.wrap(Array.tabulate(LambertGrid.nx * LambertGrid.ny)(i => (i % 255).toByte)))
  private val renderer = TileRenderer()

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
      LambertGrid.nearestIndex(LambertGrid.rhoAt(lat), sin(theta), cos(theta)) shouldBe LambertGrid.nearestIndex(lat, lon)
    }
  }

  test("tiles colour each pixel from its nearest grid point") {
    val (z, x, y) = (5, 7, 12)
    val image = pixels(renderer.render("t", layer, z, x, y))
    val worldPx = 256.0 * (1 << z)
    for (py <- 0 until 256 by 17; px <- 0 until 256 by 13) {
      val lat = toDegrees(atan(sinh(Pi * (1 - 2 * (y * 256 + py + 0.5) / worldPx))))
      val lon = (x * 256 + px + 0.5) / worldPx * 360.0 - 180.0
      val index = LambertGrid.nearestIndex(lat, lon)
      image(py)(px) shouldBe renderer.colour(layer.edrAtIndex(index))
    }
  }

  test("tiles outside the grid are transparent and not cached per tile") {
    val png = renderer.render("t", layer, 5, 20, 12) // over Asia
    pixels(png).forall(_.forall(c => (c >>> 24) == 0)) shouldBe true
    renderer.render("t", layer, 5, 21, 12) should be theSameInstanceAs png
  }

  test("prewarming renders the opening view quickly") {
    val frame = EdrFrame(FrameKind.Nowcast, Instant.EPOCH, Instant.EPOCH, SortedMap(35000 -> layer))
    TileRenderer().prewarm(frame) // JIT warm-up
    val fresh = TileRenderer()
    val start = System.nanoTime()
    fresh.prewarm(frame)
    val ms = (System.nanoTime() - start) / 1e6
    info(f"prewarm: $ms%.0f ms")
    ms should be < 2000.0
  }
}
