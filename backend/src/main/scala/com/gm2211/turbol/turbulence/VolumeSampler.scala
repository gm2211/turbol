/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.google.common.cache.{Cache, CacheBuilder}

/**
 * The 3D map's view of a frame: the grid coarsened into `step` x `step` blocks per level. A block takes the worst
 * category that covers at least [[VolumeSampler.minShare]] of its grid points, so one rough 3 km point doesn't paint
 * an 18 km block, except that any Severe or Extreme point always shows. Its EDR is the mean over the points at that
 * category or worse. Only blocks at Light or worse (medium aircraft) are kept: smooth air is most of the volume and
 * drawing it would bury the turbulence.
 *
 * @param voxels
 *   flat, 4 ints per voxel: lat * 100, lon * 100, level index into `levelsFt`, EDR byte ([[EdrLayer.encode]])
 */
final case class Volume(levelsFt: Vector[Int], step: Int, cellKm: Double, voxels: Array[Int])

final class VolumeSampler {
  private val cache: Cache[String, Volume] = CacheBuilder.newBuilder().maximumSize(40).build()
  private val t = Thresholds.byClass(AircraftClass.Medium)
  // Light, Moderate, Severe, Extreme as encoded bytes, worst last.
  private val thresholdBytes =
    Array(t.light, t.moderate, t.severe, t.extreme).map(e => EdrLayer.encode(e.toFloat) & 0xff)
  private val alwaysShown = 2 // index of Severe: any point at Severe or worse shows

  /** `cacheKey` must identify the frame. */
  def volume(cacheKey: String, frame: EdrFrame, step: Int): Volume =
    cache.get(s"$cacheKey/$step", () => sample(frame, step))

  private def sample(frame: EdrFrame, step: Int): Volume = {
    val nx = LambertGrid.nx
    val bx = (nx + step - 1) / step
    val by = (LambertGrid.ny + step - 1) / step
    val blocks = bx * by
    val centres = Array.tabulate(blocks) { b =>
      val i = math.min(b % bx * step + step / 2.0, nx - 1.0)
      val j = math.min(b / bx * step + step / 2.0, LambertGrid.ny - 1.0)
      LambertGrid.latLonAt(i, j)
    }
    val categories = thresholdBytes.length
    val out = Array.newBuilder[Int]
    frame.levels.values.zipWithIndex.foreach { case (layer, levelIndex) =>
      val valid = new Array[Int](blocks)
      val count = new Array[Int](blocks * categories) // points at category c or worse
      val sum = new Array[Int](blocks * categories)
      var j = 0
      while (j < LambertGrid.ny) {
        val row = j * nx
        val blockRow = j / step * bx
        var i = 0
        while (i < nx) {
          val v = layer.data.get(row + i) & 0xff
          if (v != 0xff) {
            val b = blockRow + i / step
            valid(b) += 1
            var c = 0
            while (c < categories && v >= thresholdBytes(c)) {
              count(b * categories + c) += 1
              sum(b * categories + c) += v
              c += 1
            }
          }
          i += 1
        }
        j += 1
      }
      var b = 0
      while (b < blocks) {
        val needed = math.max(1, math.ceil(valid(b) * VolumeSampler.minShare).toInt)
        var c = categories - 1
        while (c >= 0 && count(b * categories + c) < (if (c >= alwaysShown) 1 else needed)) c -= 1
        if (c >= 0) {
          val (lat, lon) = centres(b)
          val edr = math.round(sum(b * categories + c).toDouble / count(b * categories + c)).toInt
          out ++= Seq(math.round(lat * 100).toInt, math.round(lon * 100).toInt, levelIndex, edr)
        }
        b += 1
      }
    }
    Volume(frame.levelsFt, step, step * LambertGrid.dx / 1000, out.result())
  }
}

object VolumeSampler {
  val minShare: Double = 0.25

  /**
   * Finest block size the API serves. Step 3 needs a few hundred MB of heap to serialise the nowcast, so small
   * deployments (render.yaml, 512 MB) raise it with TURBOL_MIN_VOLUME_STEP.
   */
  val minStep: Int = sys.env.get("TURBOL_MIN_VOLUME_STEP").flatMap(_.toIntOption).getOrElse(3)
}
