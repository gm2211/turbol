/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.google.common.cache.{Cache, CacheBuilder}

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import scala.math.*

/**
 * Renders an EDR layer as 256 px Web Mercator (XYZ) map tiles, coloured by the medium-aircraft turbulence category.
 * Nearest-neighbour sampling: at the zooms people use (z3-z9) a tile pixel is coarser or close to the 3 km grid.
 */
final class TileRenderer {
  private val tileSize = 256
  private val cache: Cache[String, Array[Byte]] = CacheBuilder.newBuilder().maximumSize(3000).build()
  private val thresholds = Thresholds.byClass(AircraftClass.Medium)

  private def argb(a: Double, r: Int, g: Int, b: Int): Int = (rint(a * 255).toInt << 24) | (r << 16) | (g << 8) | b

  private val transparent = 0
  private val light = argb(0.55, 250, 204, 21)
  private val moderate = argb(0.7, 249, 115, 22)
  private val severe = argb(0.8, 220, 38, 38)
  private val extreme = argb(0.85, 147, 51, 234)

  private[turbulence] def colour(edr: Double): Int =
    if (edr.isNaN) transparent
    else if (edr < thresholds.light) argb(0.02 + 0.12 * (edr / thresholds.light), 34, 197, 94) // smooth: faint green
    else if (edr < thresholds.moderate) light
    else if (edr < thresholds.severe) moderate
    else if (edr < thresholds.extreme) severe
    else extreme

  /** PNG bytes for tile z/x/y of `layer`; `cacheKey` must identify the frame and level. */
  def render(cacheKey: String, layer: EdrLayer, z: Int, x: Int, y: Int): Array[Byte] =
    cache.get(s"$cacheKey/$z/$x/$y", () => draw(layer, z, x, y))

  private def draw(layer: EdrLayer, z: Int, x: Int, y: Int): Array[Byte] = {
    val image = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB)
    val worldPx = tileSize.toDouble * (1L << z)
    val (south, west, north, east) = LambertGrid.boundingBox
    var py = 0
    while (py < tileSize) {
      val lat = toDegrees(atan(sinh(Pi * (1 - 2 * (y * tileSize + py + 0.5) / worldPx))))
      var px = 0
      while (px < tileSize) {
        val lon = (x * tileSize + px + 0.5) / worldPx * 360.0 - 180.0
        val c =
          if (lat < south || lat > north || lon < west || lon > east) transparent
          else colour(layer.edrAtIndex(LambertGrid.nearestIndex(lat, lon)))
        image.setRGB(px, py, c)
        px += 1
      }
      py += 1
    }
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out): Unit
    out.toByteArray
  }
}
