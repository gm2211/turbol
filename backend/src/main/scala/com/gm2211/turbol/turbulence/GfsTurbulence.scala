/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import io.circe.generic.auto.*
import io.circe.parser.decode
import ucar.nc2.grib.grib2.{Grib2Record, Grib2RecordScanner}
import ucar.unidata.io.RandomAccessFile

import java.nio.file.{Files, Path}
import scala.collection.immutable.SortedMap
import scala.io.Source
import scala.math.*
import scala.util.Using

/**
 * The coarse global turbulence layer: Ellrod's TI1 index (vertical wind shear x horizontal deformation, a standard
 * GTG member) computed from NOAA GFS 0.25 degree winds and heights, then mapped to EDR by quantile matching against
 * GTG over CONUS so that turbol's thresholds give the same category frequencies on both layers.
 *
 * The mapping table and the method's verification live in data-pipeline/gfs_turbulence.py, which writes the table
 * this object loads: per altitude level, TI1 quantiles and the GTG EDR quantiles they map to.
 */
object GfsTurbulence {
  final case class Table(
    source: String,
    pressureLevelsHpa: Vector[Int],
    levelsFt: Vector[Int],
    ti1: Vector[Vector[Double]],
    edr: Vector[Vector[Double]]
  )

  lazy val table: Table = {
    val json = Using.resource(Source.fromResource("turbulence/gfs-ti1-to-edr.json"))(_.mkString)
    decode[Table](json).fold(e => throw RuntimeException("bad GFS turbulence table", e), identity)
  }

  def pressureLevelsHpa: Vector[Int] = table.pressureLevelsHpa
  def levelsFt: Vector[Int] = table.levelsFt

  /** Slope in log-log space of the last few table knots, to extend the mapping past the top one (rare, rough air). */
  private lazy val tailSlopes: Vector[Double] = table.ti1.indices.toVector.map { k =>
    val x = table.ti1(k)
    val y = table.edr(k)
    val n = x.size
    log(y(n - 1) / y(n - 4)) / log(x(n - 1) / x(n - 4))
  }

  /** EDR for a TI1 value at the `level`-th table level; NaN stays NaN. Same as `map_edr` in the pipeline script. */
  def edrFromTi1(level: Int, ti1: Double): Double = {
    val x = table.ti1(level)
    val y = table.edr(level)
    if (ti1.isNaN) Double.NaN
    else if (ti1 > x.last) min(1.0, y.last * pow(ti1 / x.last, tailSlopes(level)))
    else if (ti1 <= x.head) y.head
    else {
      var k = 1
      while (x(k) < ti1) k += 1
      y(k - 1) + (ti1 - x(k - 1)) / (x(k) - x(k - 1)) * (y(k) - y(k - 1))
    }
  }

  private val earthRadius = 6371229.0
  private val nx = GlobalGrid.nx
  private val ny = GlobalGrid.ny

  /** Horizontal deformation sqrt(stretching^2 + shearing^2) by central differences, periodic in longitude. */
  def deformation(u: Array[Float], v: Array[Float], throttle: Throttle = Throttle.off): Array[Float] = {
    val out = new Array[Float](nx * ny)
    val dLambda = toRadians(GlobalGrid.resolutionDeg)
    val dy = earthRadius * toRadians(-GlobalGrid.resolutionDeg) // rows run north to south
    var j = 0
    while (j < ny) {
      throttle.tick()
      val dx = earthRadius * max(cos(toRadians(GlobalGrid.latOf(j))), 0.05) * dLambda
      val jc = min(max(j, 1), ny - 2) // the first and last rows reuse their neighbour's north-south gradient
      val row = j * nx
      val north = (jc - 1) * nx
      val south = (jc + 1) * nx
      var i = 0
      while (i < nx) {
        val east = if (i == nx - 1) 0 else i + 1
        val west = if (i == 0) nx - 1 else i - 1
        val dudx = (u(row + east) - u(row + west)) / (2 * dx)
        val dvdx = (v(row + east) - v(row + west)) / (2 * dx)
        val dudy = (u(south + i) - u(north + i)) / (2 * dy)
        val dvdy = (v(south + i) - v(north + i)) / (2 * dy)
        val stretch = dudx - dvdy
        val shear = dvdx + dudy
        out(row + i) = sqrt(stretch * stretch + shear * shear).toFloat
        i += 1
      }
      j += 1
    }
    out
  }

  final private case class Level(u: Array[Float], v: Array[Float], z: Array[Float], deform: Array[Float])
  final private case class Layer(mid: Array[Float], ti1: Array[Float])

  /** TI1 and mid-height for the layer between two pressure levels. */
  private def layer(lo: Level, hi: Level, throttle: Throttle): Layer = {
    val n = nx * ny
    val mid = new Array[Float](n)
    val ti1 = new Array[Float](n)
    var p = 0
    while (p < n) {
      if ((p & 4095) == 0) throttle.tick()
      val dz = max(hi.z(p) - lo.z(p), 50f)
      val du = hi.u(p) - lo.u(p)
      val dv = hi.v(p) - lo.v(p)
      val vws = sqrt(du * du + dv * dv) / dz
      ti1(p) = (vws * 0.5 * (lo.deform(p) + hi.deform(p))).toFloat
      mid(p) = 0.5f * (lo.z(p) + hi.z(p))
      p += 1
    }
    Layer(mid, ti1)
  }

  // GRIB2 discipline 0: category 2 parameters 2/3 = UGRD/VGRD, category 3 parameter 5 = HGT; level type 100 = isobaric.
  private val uWind = (2, 2)
  private val vWind = (2, 3)
  private val height = (3, 5)

  /**
   * Decode a GFS file holding UGRD, VGRD and HGT on [[pressureLevelsHpa]] into EDR layers at [[levelsFt]] (MSL
   * altitude, like GTG), written to `outputDir/<levelFt>.u8` and memory-mapped. Works up the column two pressure
   * levels at a time, so only a few global fields are on the heap at once (~60 MB).
   *
   * With `wafs` (EDR per [[Wafs]] level on the same grid, see [[Wafs.fetch]]), levels WAFS covers blend it in.
   * `throttle` caps its CPU share so the server stays responsive.
   */
  def decodeToLayers(
    gribFile: Path,
    outputDir: Path,
    wafs: Option[Vector[Array[Byte]]] = None,
    throttle: Throttle = Throttle.off
  ): SortedMap[Int, EdrLayer] = {
    Files.createDirectories(outputDir)
    val n = nx * ny
    val targetsM = levelsFt.map(_ * 0.3048f)
    // Per target level, where WAFS covers it: blend table index (-1 if not), lower WAFS level, weight of the upper one.
    val wafsLevels = wafs.orNull
    val blendIndex = levelsFt.map(ft => if (wafsLevels == null) -1 else Wafs.blendLevel(ft).getOrElse(-1)).toArray
    val wafsLower = levelsFt.map(Wafs.bracket(_)._1).toArray
    val wafsUpperWeight = levelsFt.map(Wafs.bracket(_)._2).toArray
    def edrAt(t: Int, p: Int, ti1: Float): Float = {
      val ti1Edr = edrFromTi1(t, ti1)
      val b = blendIndex(t)
      if (b < 0) ti1Edr.toFloat
      else {
        val lo = EdrLayer.decode(wafsLevels(wafsLower(t))(p))
        val hi = EdrLayer.decode(wafsLevels(wafsLower(t) + 1)(p))
        if (lo.isNaN || hi.isNaN) ti1Edr.toFloat
        else {
          val w = wafsUpperWeight(t)
          Wafs.blend(b, (1 - w) * lo + w * hi, ti1Edr).toFloat
        }
      }
    }
    val outputs =
      Array.fill(levelsFt.size) { val a = new Array[Byte](n); java.util.Arrays.fill(a, EdrLayer.missing); a }

    Using.resource(new RandomAccessFile(gribFile.toString, "r")) { raf =>
      val scanner = new Grib2RecordScanner(raf)
      val records = Map.newBuilder[((Int, Int), Int), Grib2Record]
      while (scanner.hasNext) {
        val record = scanner.next()
        val pds = record.getPDS
        if (record.getDiscipline == 0 && pds.getLevelType1 == 100) {
          val pa = pds.getLevelValue1
          val hPa = rint(if (pa > 2000) pa / 100 else pa).toInt
          records += ((pds.getParameterCategory, pds.getParameterNumber), hPa) -> record
        }
      }
      val byKey = records.result()
      def field(param: (Int, Int), hPa: Int): Array[Float] = {
        val record = byKey.getOrElse((param, hPa), throw RuntimeException(s"$gribFile has no $param at $hPa hPa"))
        throttle.tick()
        val values = record.readData(raf)
        throttle.tick()
        require(values.length == n, s"unexpected grid size ${values.length} in $gribFile")
        values
      }
      def level(hPa: Int): Level = {
        val u = field(uWind, hPa)
        val v = field(vWind, hPa)
        Level(u, v, field(height, hPa), deformation(u, v, throttle))
      }

      val bottomUp = pressureLevelsHpa.sorted(using Ordering[Int].reverse)
      val layerCount = bottomUp.size - 1
      var lo = level(bottomUp.head)
      var previous: Layer = null
      for (k <- 1 until bottomUp.size) {
        val hi = level(bottomUp(k))
        val current = layer(lo, hi, throttle)
        // Linear in height between layer mids, holding the end values below the first and above the last mid.
        if (previous != null) {
          val first = k == 2
          val last = k == layerCount
          var p = 0
          while (p < n) {
            if ((p & 1023) == 0) throttle.tick()
            val z0 = previous.mid(p)
            val z1 = current.mid(p)
            var t = 0
            while (t < targetsM.size) {
              val h = targetsM(t)
              val ti1 =
                if (h > z0 && h <= z1) previous.ti1(p) + (h - z0) / max(z1 - z0, 1f) *
                  (current.ti1(p) - previous.ti1(p))
                else if (first && h <= z0) previous.ti1(p)
                else if (last && h > z1) current.ti1(p)
                else Float.NaN
              if (!ti1.isNaN) outputs(t)(p) = EdrLayer.encode(edrAt(t, p, ti1))
              t += 1
            }
            p += 1
          }
        }
        previous = current
        lo = hi
      }
    }

    SortedMap.from(levelsFt.zip(outputs).map { case (levelFt, bytes) =>
      val file = outputDir.resolve(s"$levelFt.u8")
      Files.write(file, bytes)
      levelFt -> GribDecoder.mapLayer(file, levelFt)
    })
  }
}
