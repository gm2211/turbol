/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.io.ByteArrayOutputStream
import java.time.{Duration, Instant}
import java.util.zip.{Deflater, GZIPOutputStream}
import scala.math.*

/**
 * The cells of a Flight Pack: rows about every 6 km along the great circle from origin to destination, and columns
 * every 6 km across it out to ±240 km, where 90-96% of real cruise minutes fall (design doc §7). Cell (row, col) is
 * the point reached by going `row * alongStepKm` along the route, then `(col - halfCols) * crossStepKm` at right
 * angles to it (positive is left of the direction of travel). The phone finds a location's cell with [[locate]].
 */
final case class PackGrid(
  originLat: Double,
  originLon: Double,
  destLat: Double,
  destLon: Double,
  targetAlongStepKm: Double = PackGrid.stepKm,
  crossStepKm: Double = PackGrid.stepKm,
  halfWidthKm: Double = PackGrid.halfWidthKm
) {
  import PackGrid.*

  val totalKm: Double = RouteAnalyzer.greatCircleKm(originLat, originLon, destLat, destLon)
  val rows: Int = ceil(totalKm / targetAlongStepKm).toInt + 1
  val alongStepKm: Double = totalKm / (rows - 1)
  val halfCols: Int = rint(halfWidthKm / crossStepKm).toInt
  val cols: Int = 2 * halfCols + 1

  // Orthonormal frame: a is the origin, n the pole of the route's great circle, t the direction of travel at a.
  private val a = unit(originLat, originLon)
  private val n = normalize(crossProduct(a, unit(destLat, destLon)))
  private val t = crossProduct(n, a)

  def pointAt(alongKm: Double, crossKm: Double): (Double, Double) = {
    val (s, c) = (alongKm / earthRadiusKm, crossKm / earthRadiusKm)
    val (cs, ss, cc, sc) = (cos(s), sin(s), cos(c), sin(c))
    toLatLon(
      Vec(
        (a.x * cs + t.x * ss) * cc + n.x * sc,
        (a.y * cs + t.y * ss) * cc + n.y * sc,
        (a.z * cs + t.z * ss) * cc + n.z * sc
      )
    )
  }

  def cellCenter(row: Int, col: Int): (Double, Double) = pointAt(row * alongStepKm, (col - halfCols) * crossStepKm)

  /** (along, cross) in km: the inverse of [[pointAt]]. */
  def locate(lat: Double, lon: Double): (Double, Double) = {
    val x = unit(lat, lon)
    (earthRadiusKm * atan2(dot(x, t), dot(x, a)), earthRadiusKm * asin(max(-1.0, min(1.0, dot(x, n)))))
  }

  /** The nearest cell, or None outside the pack. */
  def cellOf(lat: Double, lon: Double): Option[(Int, Int)] = {
    val (along, across) = locate(lat, lon)
    val row = rint(along / alongStepKm).toInt
    val col = rint(across / crossStepKm).toInt + halfCols
    if (row >= 0 && row < rows && col >= 0 && col < cols) Some((row, col)) else None
  }
}

object PackGrid {
  val stepKm = 6.0
  val halfWidthKm = 240.0
  private val earthRadiusKm = 6371.0 // as RouteAnalyzer

  final private case class Vec(x: Double, y: Double, z: Double)

  private def unit(lat: Double, lon: Double): Vec = {
    val (p, l) = (toRadians(lat), toRadians(lon))
    Vec(cos(p) * cos(l), cos(p) * sin(l), sin(p))
  }
  private def crossProduct(u: Vec, v: Vec): Vec =
    Vec(u.y * v.z - u.z * v.y, u.z * v.x - u.x * v.z, u.x * v.y - u.y * v.x)
  private def dot(u: Vec, v: Vec): Double = u.x * v.x + u.y * v.y + u.z * v.z
  private def normalize(v: Vec): Vec = {
    val len = sqrt(dot(v, v))
    Vec(v.x / len, v.y / len, v.z / len)
  }
  private def toLatLon(v: Vec): (Double, Double) =
    (toDegrees(atan2(v.z, sqrt(v.x * v.x + v.y * v.y))), toDegrees(atan2(v.y, v.x)))
}

/** Which rows of the pack one frame covers. */
final case class PackSlab(frame: EdrFrame, rowStart: Int, rowEnd: Int)

/**
 * A Flight Pack is the raw EDR a phone needs to work out a flight's turbulence with no network: for every frame the
 * flight can use (the GTGN nowcast, each GTG forecast hour, and each frame of the coarse global layer that answers
 * outside and past GTG), the rows of the [[PackGrid]] the plane can be over while that frame counts, on the
 * forecast's 12 levels. One slab per frame, so a new nowcast, GTG cycle or GFS cycle replaces only the slabs whose
 * frame it supersedes.
 *
 * A slab is `levels * rows * cols` bytes, laid out [level][row][col], each byte an [[EdrLayer]] code (EDR * 200, 255
 * missing). Within each level every row after the first is stored as its difference from the row before (mod 256),
 * which makes it compress about a fifth better, since neighbouring rows are 6 km apart.
 */
object FlightPack {
  /** 2: global-layer slabs, and GTG cells straddling GTG's edge take the edge value. */
  val version = 2

  /** The forecast's levels: the nowcast has 51 levels, but the pack keeps the 12 the forecast has. */
  val levelsFt: Vector[Int] = TurbulenceDataUpdater.forecastLevelsFt.toVector.sorted

  /** Takeoff can be this much earlier or later than planned and the pack still covers the flight. */
  val takeoffSlackMinutes = 90.0

  /** Shortest route a pack is built for: below this the grid is degenerate. */
  val minRouteKm = 20.0

  /**
   * Slabs for a flight planned to take off at `departure`. A frame counts from `before` to `after` its valid time:
   * the nowcast from 2 h before to 1 h after (TurbulenceStore.nowcastWeight), a forecast hour between its neighbours
   * (time interpolation), and the first forecast hour also up to 90 min before it. A global frame counts between its
   * neighbours too, and the first and last up to 3 h beyond them (TurbulenceStore.globalAt).
   */
  def slabs(grid: PackGrid, data: TurbulenceData, departure: Instant): Seq[PackSlab] = {
    val minutesAtRow = plannedMinutes(grid)
    def rowsFor(valid: Instant, beforeMin: Double, afterMin: Double): Option[(Int, Int)] = {
      val center = Duration.between(departure, valid).toSeconds / 60.0
      val (lo, hi) = (center - beforeMin - takeoffSlackMinutes, center + afterMin + takeoffSlackMinutes)
      val first = minutesAtRow.indexWhere(_ >= lo)
      val last = minutesAtRow.lastIndexWhere(_ <= hi)
      if (first < 0 || last < first) None else Some((first, last + 1))
    }
    val nowcast = data.nowcast.flatMap(f => rowsFor(f.validTime, 120, 60).map { case (s, e) => PackSlab(f, s, e) })
    val forecast = data.forecast.indices.flatMap { k =>
      val frame = data.forecast(k)
      val before = if (k == 0) 90.0 else minutesBetween(data.forecast(k - 1).validTime, frame.validTime)
      val after =
        if (k == data.forecast.size - 1) 0.0 else minutesBetween(frame.validTime, data.forecast(k + 1).validTime)
      rowsFor(frame.validTime, before, after).map { case (s, e) => PackSlab(frame, s, e) }
    }
    val slackMinutes = TurbulenceStore.globalSlackHours * 60
    val global = data.global.indices.flatMap { k =>
      val frame = data.global(k)
      val before = if (k == 0) slackMinutes else minutesBetween(data.global(k - 1).validTime, frame.validTime)
      val after =
        if (k == data.global.size - 1) slackMinutes else minutesBetween(frame.validTime, data.global(k + 1).validTime)
      rowsFor(frame.validTime, before, after).map { case (s, e) => PackSlab(frame, s, e) }
    }
    nowcast.toSeq ++ forecast ++ global
  }

  /** Minutes after takeoff the flight profile reaches each row (RouteAnalyzer's gate-to-gate profile). */
  def plannedMinutes(grid: PackGrid): IndexedSeq[Double] = {
    val minutesAt = RouteAnalyzer.plannedMinutesAt(grid.totalKm)
    (0 until grid.rows).map(r => minutesAt(r * grid.alongStepKm))
  }

  private def minutesBetween(a: Instant, b: Instant): Double = Duration.between(a, b).toSeconds / 60.0

  /** How far outside GTG's grid, in grid points, a cell's centre can be and still take the value at the edge. */
  val edgeMarginPoints = 2

  /**
   * The grid point a cell of a `kind` frame takes its value from: the one nearest the cell's centre. A GTG cell whose
   * centre is just outside GTG's grid takes the nearest point on the edge, so a location just inside GTG always finds
   * GTG data in its cell (cells are 6 km, GTG's points 3 km apart); the phone, like the server, only uses GTG where the
   * location itself is inside the grid.
   */
  def cellIndex(kind: FrameKind, lat: Double, lon: Double): Int = kind match {
    case FrameKind.Global => GlobalGrid.nearestIndex(lat, lon)
    case _ =>
      if (lat < 0 || lat > 89) -1
      else {
        val (fi, fj) = LambertGrid.fractionalIndex(lat, lon)
        val (i, j) = (rint(fi).toInt, rint(fj).toInt)
        val m = edgeMarginPoints
        if (i < -m || i >= LambertGrid.nx + m || j < -m || j >= LambertGrid.ny + m) -1
        else min(max(j, 0), LambertGrid.ny - 1) * LambertGrid.nx + min(max(i, 0), LambertGrid.nx - 1)
      }
  }

  /** The slab's bytes, delta-coded along the route (see the object doc). */
  def encode(grid: PackGrid, slab: PackSlab): Array[Byte] = {
    val rowCount = slab.rowEnd - slab.rowStart
    val flatIndex = Array.tabulate(rowCount * grid.cols) { k =>
      val (lat, lon) = grid.cellCenter(slab.rowStart + k / grid.cols, k % grid.cols)
      cellIndex(slab.frame.kind, lat, lon)
    }
    val out = new Array[Byte](levelsFt.size * rowCount * grid.cols)
    levelsFt.zipWithIndex.foreach { case (level, li) =>
      val layer = slab.frame.levels.get(level)
      val base = li * rowCount * grid.cols
      flatIndex.indices.foreach { k =>
        val idx = flatIndex(k)
        out(base + k) = layer.filter(_ => idx >= 0).fold(EdrLayer.missing)(_.data.get(idx))
      }
      // Delta along the route, last row first so each row is subtracted from the original row before it.
      (rowCount - 1 until 0 by -1).foreach { r =>
        val row = base + r * grid.cols
        (0 until grid.cols).foreach(c => out(row + c) = (out(row + c) - out(row - grid.cols + c)).toByte)
      }
    }
    out
  }

  /** Undo [[encode]]'s delta coding, in place. */
  def decode(bytes: Array[Byte], rowCount: Int, cols: Int): Array[Byte] = {
    val perLevel = rowCount * cols
    (0 until bytes.length / math.max(1, perLevel)).foreach { li =>
      val base = li * perLevel
      (1 until rowCount).foreach { r =>
        val row = base + r * cols
        (0 until cols).foreach(c => bytes(row + c) = (bytes(row + c) + bytes(row - cols + c)).toByte)
      }
    }
    bytes
  }

  def gzip(bytes: Array[Byte]): Array[Byte] = {
    val buffer = new ByteArrayOutputStream(bytes.length / 4 + 64)
    val out = new GZIPOutputStream(buffer) {
      `def`.setLevel(Deflater.BEST_COMPRESSION)
    }
    try out.write(bytes)
    finally out.close()
    buffer.toByteArray
  }
}
