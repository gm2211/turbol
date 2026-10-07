/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.nio.ByteBuffer
import java.time.Instant
import scala.collection.immutable.SortedMap

/**
 * EDR on one altitude level of the HRRR grid, stored one byte per grid point (see [[EdrLayer.encode]]) so a full
 * nowcast (51 levels) is ~100 MB and can live in a memory-mapped file instead of on the heap.
 */
final case class EdrLayer(levelFt: Int, data: ByteBuffer) {
  def edrAtIndex(flatIndex: Int): Double =
    if (flatIndex < 0) Double.NaN else EdrLayer.decode(data.get(flatIndex))
}

object EdrLayer {
  val missing: Byte = 0xff.toByte
  private val scale = 200.0 // 0.005 resolution, max 1.27: GRIB packs EDR with ~6 bits, so this is lossless enough

  def encode(edr: Float): Byte =
    if (edr.isNaN || edr < 0 || edr >= 9999) missing
    else math.min(254, math.round(edr * scale)).toByte

  def decode(b: Byte): Double = if (b == missing) Double.NaN else (b & 0xff) / scale
}

enum FrameKind {
  case Nowcast, Forecast
}

/**
 * All decoded levels valid at one time: either a GTGN nowcast or one forecast hour of a GTG cycle.
 *
 * @param issued
 *   the GTG cycle init time for forecasts, or the valid time for nowcasts
 */
final case class EdrFrame(kind: FrameKind, validTime: Instant, issued: Instant, levels: SortedMap[Int, EdrLayer]) {
  val levelsFt: Vector[Int] = levels.keys.toVector

  /** EDR at a location and altitude, linearly interpolated between the two nearest levels. NaN when no data. */
  def edrAt(lat: Double, lon: Double, altitudeFt: Double): Double =
    edrAtIndex(LambertGrid.nearestIndex(lat, lon), altitudeFt)

  def edrAtIndex(flatIndex: Int, altitudeFt: Double): Double = {
    if (flatIndex < 0 || levels.isEmpty) return Double.NaN
    val below = levels.maxBefore(altitudeFt.toInt + 1)
    val above = levels.minAfter(altitudeFt.toInt + 1)
    (below, above) match {
      case (Some((lo, loLayer)), Some((hi, hiLayer))) =>
        val a = loLayer.edrAtIndex(flatIndex)
        val b = hiLayer.edrAtIndex(flatIndex)
        if (a.isNaN) b
        else if (b.isNaN) a
        else {
          val w = (altitudeFt - lo) / (hi - lo).toDouble
          a + w * (b - a)
        }
      case (Some((_, layer)), None) => layer.edrAtIndex(flatIndex)
      case (None, Some((_, layer))) => layer.edrAtIndex(flatIndex)
      case (None, None) => Double.NaN
    }
  }

  /** The layer closest to an altitude, for map rendering. */
  def nearestLayer(altitudeFt: Int): Option[EdrLayer] =
    levels.minByOption { case (lvl, _) => math.abs(lvl - altitudeFt) }.map(_._2)
}
