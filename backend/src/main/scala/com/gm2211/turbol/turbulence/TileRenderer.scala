/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.google.common.cache.{Cache, CacheBuilder}

import java.awt.image.{BufferedImage, DataBufferByte, IndexColorModel}
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import scala.math.*

/**
 * Renders an EDR layer as 256 px Web Mercator (XYZ) map tiles, coloured by the medium-aircraft turbulence category.
 * Nearest-neighbour sampling: at the zooms people use (z3-z9) a tile pixel is coarser or close to the 3 km grid. The
 * global layer is drawn nearest-neighbour too, so its 28 km cells show as blocks: the map doesn't pretend it is finer.
 *
 * The production instance has a tenth of a CPU, so a tile is cheap by construction: the projection is computed per row
 * and per column rather than per pixel, layers are already one byte per grid point so those bytes index a 256-colour
 * palette directly, and tiles outside every grid share one empty PNG.
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

  // Palette index = the layer's stored byte (EdrLayer.encode); EdrLayer.missing (255) doubles as "outside the grid".
  private val palette: IndexColorModel = {
    val argbs = Array.tabulate(256)(i => colour(EdrLayer.decode(i.toByte)))
    new IndexColorModel(
      8,
      256,
      argbs.map(c => ((c >> 16) & 0xff).toByte),
      argbs.map(c => ((c >> 8) & 0xff).toByte),
      argbs.map(c => (c & 0xff).toByte),
      argbs.map(c => ((c >>> 24) & 0xff).toByte)
    )
  }
  private val noData = EdrLayer.missing

  private def encode(image: BufferedImage): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out): Unit
    out.toByteArray
  }

  private def newTile(): (BufferedImage, Array[Byte]) = {
    val image = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_BYTE_INDEXED, palette)
    (image, image.getRaster.getDataBuffer.asInstanceOf[DataBufferByte].getData)
  }

  private val emptyTile: Array[Byte] = {
    val (image, pixels) = newTile()
    java.util.Arrays.fill(pixels, noData)
    encode(image)
  }

  /**
   * PNG bytes for tile z/x/y of `frame` at the level nearest `levelFt`, with `global` (the coarse layer valid closest
   * to it) filling in outside GTG's grid and blended across its edge.
   */
  def render(frame: EdrFrame, global: Option[EdrFrame], levelFt: Int, z: Int, x: Int, y: Int): Array[Byte] = {
    val gtgLayer = frame.nearestLayer(levelFt)
    val globalLayer = global.flatMap(_.nearestLayer(levelFt))
    val key = s"${frame.id}/${global.fold("-")(_.id)}/${gtgLayer.fold(0)(_.levelFt)}/${globalLayer.fold(0)(_.levelFt)}"
    if (!validTile(z, x, y) || (globalLayer.isEmpty && !overlapsGrid(z, x, y))) emptyTile
    else cache.get(s"$key/$z/$x/$y", () => draw(gtgLayer, globalLayer, z, x, y))
  }

  /**
   * Renders the tiles of the map's opening view (all of CONUS at zooms 4-5, at FL350) for a new frame, so the first
   * visitor after each 15-minute nowcast or 3-hourly forecast update doesn't wait for them, and the forecast time
   * slider has every hour ready.
   */
  def prewarm(frame: EdrFrame, global: Option[EdrFrame], levelFt: Int = 35000): Unit =
    for {
      z <- 4 to 5
      n = 1 << z
      x <- 0 until n
      y <- 0 until n
      if overlapsGrid(z, x, y)
    } render(frame, global, levelFt, z, x, y): Unit

  private def worldPx(z: Int): Double = tileSize.toDouble * (1L << z)
  private def latAt(z: Int, py: Double): Double = toDegrees(atan(sinh(Pi * (1 - 2 * py / worldPx(z)))))
  private def lonAt(z: Int, px: Double): Double = px / worldPx(z) * 360.0 - 180.0

  private def validTile(z: Int, x: Int, y: Int): Boolean = {
    val n = 1L << z
    x >= 0 && y >= 0 && x < n && y < n
  }

  private def overlapsGrid(z: Int, x: Int, y: Int): Boolean = {
    val (south, west, north, east) = LambertGrid.boundingBox
    validTile(z, x, y) &&
    latAt(z, y * tileSize) >= south && latAt(z, (y + 1) * tileSize) <= north &&
    lonAt(z, (x + 1) * tileSize) >= west && lonAt(z, x * tileSize) <= east
  }

  // Per pixel: GTG's byte deep inside its grid; the global layer's outside it; across the last blendCells cells, a mix
  // of the two weighted by distance to the edge (bytes are linear in EDR, so mixing bytes mixes EDR).
  private def draw(gtg: Option[EdrLayer], global: Option[EdrLayer], z: Int, x: Int, y: Int): Array[Byte] = {
    val (image, pixels) = newTile()
    val (south, west, north, east) = LambertGrid.boundingBox
    val gtgData = gtg.map(_.data).orNull
    val globalData = global.map(_.data).orNull
    val sinTheta = new Array[Double](tileSize)
    val cosTheta = new Array[Double](tileSize)
    val inLon = new Array[Boolean](tileSize)
    val globalCol = new Array[Int](tileSize)
    var px = 0
    while (px < tileSize) {
      val lon = lonAt(z, x * tileSize + px + 0.5)
      val theta = LambertGrid.thetaAt(lon)
      sinTheta(px) = sin(theta)
      cosTheta(px) = cos(theta)
      inLon(px) = gtgData != null && lon >= west && lon <= east
      globalCol(px) = GlobalGrid.colAt(lon)
      px += 1
    }
    val blend = LambertGrid.blendCells
    var py = 0
    while (py < tileSize) {
      val lat = latAt(z, y * tileSize + py + 0.5)
      val row = py * tileSize
      val inLat = lat >= south && lat <= north
      val rho = if (inLat) LambertGrid.rhoAt(lat) else 0.0
      val globalRow = GlobalGrid.rowAt(lat) * GlobalGrid.nx
      px = 0
      while (px < tileSize) {
        var edge = -1.0
        var g = noData
        if (inLat && inLon(px)) {
          val (fi, fj) = LambertGrid.fractionalIndex(rho, sinTheta(px), cosTheta(px))
          val i = rint(fi).toInt
          val j = rint(fj).toInt
          if (i >= 0 && i < LambertGrid.nx && j >= 0 && j < LambertGrid.ny) {
            edge = LambertGrid.edgeDistance(fi, fj)
            g = gtgData.get(j * LambertGrid.nx + i)
          }
        }
        pixels(row + px) =
          if (g != noData && edge >= blend) g
          else {
            val o = if (globalData == null) noData else globalData.get(globalRow + globalCol(px))
            if (o == noData) g
            else if (g == noData) o
            else {
              val w = max(0.0, edge / blend)
              rint(w * (g & 0xff) + (1 - w) * (o & 0xff)).toInt.toByte
            }
          }
        px += 1
      }
      py += 1
    }
    encode(image)
  }
}
