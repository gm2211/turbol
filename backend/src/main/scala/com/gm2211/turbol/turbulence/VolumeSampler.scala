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
 * @param globalVoxels
 *   the same for the coarse global layer outside GTG's grid, in blocks of `globalCellKm`, level index into
 *   `globalLevelsFt`
 */
final case class Volume(
  levelsFt: Vector[Int],
  step: Int,
  cellKm: Double,
  voxels: Array[Int],
  globalLevelsFt: Vector[Int] = Vector.empty,
  globalCellKm: Double = 0,
  globalVoxels: Array[Int] = Array.emptyIntArray
) {

  /**
   * The JSON the 3D map reads, written straight into a string: a whole-country volume is ~3 million numbers, and a
   * JSON tree of them took ~150 MB of heap per request.
   */
  def toJsonString: String = {
    val out = new java.lang.StringBuilder(16 + 4 * (voxels.length + globalVoxels.length))
    def ints(name: String, values: Array[Int]): Unit = {
      out.append('"').append(name).append("\":[")
      var k = 0
      while (k < values.length) {
        if (k > 0) out.append(','): Unit
        out.append(values(k))
        k += 1
      }
      out.append(']'): Unit
    }
    out.append('{')
    ints("levelsFt", levelsFt.toArray)
    out.append(",\"step\":").append(step).append(",\"cellKm\":").append(cellKm).append(',')
    ints("voxels", voxels)
    out.append(',')
    ints("globalLevelsFt", globalLevelsFt.toArray)
    out.append(",\"globalCellKm\":").append(globalCellKm).append(',')
    ints("globalVoxels", globalVoxels)
    out.append('}').toString
  }
}

final class VolumeSampler {
  // Encoded volumes, capped by size: a whole-country one is ~13 MB of JSON, so a count cap of 40 could hold ~500 MB.
  private val cache: Cache[String, String] = CacheBuilder
    .newBuilder()
    .maximumWeight(VolumeSampler.cacheChars)
    .weigher((_: String, json: String) => json.length)
    .build()
  private val t = Thresholds.byClass(AircraftClass.Medium)
  // Light, Moderate, Severe, Extreme as encoded bytes, worst last.
  private val thresholdBytes =
    Array(t.light, t.moderate, t.severe, t.extreme).map(e => EdrLayer.encode(e.toFloat) & 0xff)
  private val alwaysShown = 2 // index of Severe: any point at Severe or worse shows

  def volume(frame: EdrFrame, global: Option[EdrFrame], step: Int): Volume = sample(frame, global, step)

  /** [[Volume.toJsonString]] of [[volume]], cached. */
  def volumeJson(frame: EdrFrame, global: Option[EdrFrame], step: Int): String =
    cache.get(s"${frame.id}/${global.fold("-")(_.id)}/$step", () => sample(frame, global, step).toJsonString)

  private def sample(frame: EdrFrame, global: Option[EdrFrame], step: Int): Volume = {
    val gtg = blocks(frame, LambertGrid.nx, LambertGrid.ny, step, (i, j) => Some(LambertGrid.latLonAt(i, j)))
    val globalVoxels = global.fold(Array.emptyIntArray) { g =>
      // Only blocks whose centre is off GTG's grid, and not over the poles where nobody flies.
      blocks(
        g,
        GlobalGrid.nx,
        GlobalGrid.ny,
        VolumeSampler.globalStep,
        (i, j) => {
          val lat = GlobalGrid.latOf(math.rint(j).toInt)
          val lon = GlobalGrid.lonOf(math.rint(i).toInt)
          if (math.abs(lat) > 80 || LambertGrid.nearestIndex(lat, lon) >= 0) None else Some((lat, lon))
        }
      )
    }
    Volume(
      frame.levelsFt,
      step,
      step * LambertGrid.dx / 1000,
      gtg,
      global.fold(Vector.empty[Int])(_.levelsFt),
      VolumeSampler.globalStep * GlobalGrid.resolutionKm,
      globalVoxels
    )
  }

  /** Voxels for `step` x `step` blocks of an nx x ny grid; `centre` gives a block centre's lat/lon, None to skip it. */
  private def blocks(
    frame: EdrFrame,
    nx: Int,
    ny: Int,
    step: Int,
    centre: (Double, Double) => Option[(Double, Double)]
  ): Array[Int] = {
    val bx = (nx + step - 1) / step
    val by = (ny + step - 1) / step
    val blocks = bx * by
    val centres = Array.tabulate(blocks) { b =>
      val i = math.min(b % bx * step + step / 2.0, nx - 1.0)
      val j = math.min(b / bx * step + step / 2.0, ny - 1.0)
      centre(i, j).orNull
    }
    val categories = thresholdBytes.length
    val out = Array.newBuilder[Int]
    frame.levels.values.zipWithIndex.foreach { case (layer, levelIndex) =>
      val valid = new Array[Int](blocks)
      val count = new Array[Int](blocks * categories) // points at category c or worse
      val sum = new Array[Int](blocks * categories)
      var j = 0
      while (j < ny) {
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
        if (centres(b) != null) {
          val needed = math.max(1, math.ceil(valid(b) * VolumeSampler.minShare).toInt)
          var c = categories - 1
          while (c >= 0 && count(b * categories + c) < (if (c >= alwaysShown) 1 else needed)) c -= 1
          if (c >= 0) {
            val (lat, lon) = centres(b)
            val edr = math.round(sum(b * categories + c).toDouble / count(b * categories + c)).toInt
            out ++= Seq(math.round(lat * 100).toInt, math.round(lon * 100).toInt, levelIndex, edr)
          }
        }
        b += 1
      }
    }
    out.result()
  }
}

object VolumeSampler {
  val minShare: Double = 0.25

  /** Volume JSON kept in memory across frames and steps: ~3 whole-country volumes. */
  val cacheChars: Long = 40_000_000L

  /**
   * Global-layer blocks are 6 x 6 GFS cells (1.5 degrees, ~170 km): the whole world at 0.25 degrees is far too many,
   * and 1-degree blocks added ~2.4 MB (17%) to the volume JSON; these add about 1 MB.
   */
  val globalStep: Int = 6

  /**
   * Finest block size the API serves. Step 3 needs a few hundred MB of heap to serialise the nowcast, so small
   * deployments (render.yaml, 512 MB) raise it with TURBOL_MIN_VOLUME_STEP.
   */
  val minStep: Int = sys.env.get("TURBOL_MIN_VOLUME_STEP").flatMap(_.toIntOption).getOrElse(3)
}
