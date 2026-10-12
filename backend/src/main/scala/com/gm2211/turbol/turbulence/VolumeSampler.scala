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
    // ~5 chars per number on average ("-12264," is 7, ",0" is 2), so the buffer rarely has to double.
    val out = new java.lang.StringBuilder(256 + 5 * (voxels.length + globalVoxels.length))
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

  /**
   * The compact form the 3D map reads, ~4x smaller than the JSON once gzipped. Little-endian:
   *
   * {{{
   * int32 magic "TVB1"
   * per set (GTG, then global): int32 levelCount, int32 levelsFt[levelCount], float32 cellKm, int32 n
   * per set: int16 lat*100 delta[n], int16 lon*100 delta[n], uint8 level index[n], uint8 EDR byte[n]
   * }}}
   *
   * Each lat/lon is the change from the previous voxel's (the first from 0), wrapping around in 16 bits (a jump from
   * lon -179 to 179 overflows; adding it back with the same wrap restores the value): neighbouring blocks are close,
   * so the deltas are small and gzip squeezes them hard.
   */
  def toBinary: Array[Byte] = {
    def header(levels: Vector[Int]) = 4 * (levels.length + 3)
    def body(vox: Array[Int]) = vox.length / 4 * 6
    val size = 4 + header(levelsFt) + header(globalLevelsFt) + body(voxels) + body(globalVoxels)
    val buf = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buf.put("TVB1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
    def head(levels: Vector[Int], km: Double, vox: Array[Int]): Unit = {
      buf.putInt(levels.length)
      levels.foreach(buf.putInt)
      buf.putFloat(km.toFloat).putInt(vox.length / 4): Unit
    }
    def put(vox: Array[Int]): Unit = {
      val n = vox.length / 4
      for (field <- 0 to 1) {
        var prev = 0
        var k = 0
        while (k < n) {
          val v = vox(k * 4 + field)
          buf.putShort((v - prev).toShort)
          prev = v
          k += 1
        }
      }
      for (field <- 2 to 3) {
        var k = 0
        while (k < n) {
          buf.put(vox(k * 4 + field).toByte)
          k += 1
        }
      }
    }
    head(levelsFt, cellKm, voxels)
    head(globalLevelsFt, globalCellKm, globalVoxels)
    put(voxels)
    put(globalVoxels)
    buf.array()
  }
}

final class VolumeSampler {
  // Encoded volumes, capped by size: a count cap of 40 could hold ~100 MB at the production step, ~500 MB at finer ones.
  private val cache: Cache[String, String] = CacheBuilder
    .newBuilder()
    .maximumWeight(VolumeSampler.cacheChars)
    .weigher((_: String, json: String) => json.length)
    .build()
  // One volume build at a time (binary or JSON): each holds tens of MB while it runs, and the heap is sized for one.
  private val building = new Object
  private val t = Thresholds.byClass(AircraftClass.Medium)
  // Light, Moderate, Severe, Extreme as encoded bytes, worst last.
  private val thresholdBytes =
    Array(t.light, t.moderate, t.severe, t.extreme).map(e => EdrLayer.encode(e.toFloat) & 0xff)
  private val alwaysShown = 2 // index of Severe: any point at Severe or worse shows

  // Gzipped [[Volume.toBinary]]: built and compressed once per frame, so a request just copies bytes out.
  private val binaryCache: Cache[String, Array[Byte]] = CacheBuilder
    .newBuilder()
    .maximumWeight(VolumeSampler.cacheBinaryBytes)
    .weigher((_: String, bytes: Array[Byte]) => bytes.length)
    .build()

  def volume(frame: EdrFrame, global: Option[EdrFrame], step: Int): Volume = sample(frame, global, step)

  /** [[Volume.toBinary]] of [[volume]], gzipped and cached: serve it with `Content-Encoding: gzip`. */
  def volumeGzip(frame: EdrFrame, global: Option[EdrFrame], step: Int): Array[Byte] =
    binaryCache.get(
      s"${frame.id}/${global.fold("-")(_.id)}/$step",
      () =>
        building.synchronized {
          val raw = sample(frame, global, step).toBinary
          val out = new java.io.ByteArrayOutputStream(raw.length / 3)
          val gz = new java.util.zip.GZIPOutputStream(out) { `def`.setLevel(java.util.zip.Deflater.BEST_COMPRESSION) }
          gz.write(raw)
          gz.close()
          out.toByteArray
        }
    )

  /** Builds the volume a new frame will be opened with in 3D, so the first visitor doesn't wait for it. */
  def prewarm(frame: EdrFrame, global: Option[EdrFrame]): Unit =
    volumeGzip(frame, global, VolumeSampler.defaultStep): Unit

  /** [[Volume.toJsonString]] of [[volume]], cached. */
  def volumeJson(frame: EdrFrame, global: Option[EdrFrame], step: Int): String =
    cache.get(
      s"${frame.id}/${global.fold("-")(_.id)}/$step",
      () => building.synchronized(sample(frame, global, step).toJsonString)
    )

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

  /** Gzipped binary volumes kept in memory: a step-6 country with the global layer is ~0.3 MB. */
  val cacheBinaryBytes: Long = 8_000_000L

  /** Block size the 3D map asks for. */
  val defaultStep: Int = 6

  /**
   * Volume JSON kept in memory across frames and steps (~2.5 MB each at the production step, so ~6). JSON is ASCII,
   * so a char is a byte of heap.
   */
  val cacheChars: Long = 16_000_000L

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
