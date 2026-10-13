/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import io.circe.generic.auto.*
import io.circe.parser.decode

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO
import scala.io.Source
import scala.math.*
import scala.util.Using

/**
 * WAFS (World Area Forecast System) turbulence EDR for the global layer, as the Aviation Weather Center publishes it
 * for its public viewer (https://aviationweather.gov/wafs/). The gridded GRIB2 needs an approved account, so this
 * reads AWC's images instead: one 900x600 Mercator PNG (lat +-75.94) per level and forecast hour, coloured on a fixed
 * ramp. A ramp step maps to EDR, and the result is blended with the GFS TI1 layer (see [[GfsTurbulence]]).
 *
 * The step -> EDR and blend tables come from data-pipeline/wafs_blend.py, fitted against GTG over CONUS.
 */
object Wafs {
  final case class Table(
    source: String,
    levelsHpa: Vector[Int],
    levelsFl: Vector[Int],
    maxLat: Double,
    weight: Double,
    ramp: Vector[Vector[Int]],
    stepEdr: Vector[Double],
    levelsFt: Vector[Int],
    mixed: Vector[Vector[Double]],
    edr: Vector[Vector[Double]]
  )

  lazy val table: Table = {
    val json = Using.resource(Source.fromResource("turbulence/wafs-blend.json"))(_.mkString)
    decode[Table](json).fold(e => throw RuntimeException("bad WAFS blend table", e), identity)
  }

  /** WAFS images exist from 6 to 48 hours ahead, every 3 hours, for cycles every 6 hours. */
  val minLeadHours = 6
  val maxLeadHours = 48

  private val dayFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
  private val hourFormat = DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC)

  def url(cycle: Instant, leadHours: Int, levelHpa: Int): String = {
    val day = dayFormat.format(cycle)
    val hour = hourFormat.format(cycle)
    f"https://aviationweather.gov/data/products/wafs/$day/$hour/${day}_${hour}_F$leadHours%02d_wafs_${levelHpa}_edr_m.png"
  }

  /** The newest cycle (6-hourly, at or before `latestCycle`) that has a forecast for `valid`, with its lead time. */
  def cyclesFor(valid: Instant, latestCycle: Instant): Seq[(Instant, Int)] = (0 to 2).map(k =>
    latestCycle.minus(6L * k, ChronoUnit.HOURS)
  ).flatMap { cycle =>
    val lead = ChronoUnit.HOURS.between(cycle, valid).toInt
    Option.when(lead >= minLeadHours && lead <= maxLeadHours && lead % 3 == 0)(cycle -> lead)
  }

  /**
   * WAFS EDR at every level for `valid`, on the GFS 0.25 degree grid ([[EdrLayer.encode]]d, missing where the images
   * don't reach), from the newest cycle up to `latestCycle` that has every level; None when no cycle has them all.
   */
  def fetch(
    valid: Instant,
    latestCycle: Instant,
    throttle: Throttle = Throttle.off
  ): Option[(Instant, Vector[Array[Byte]])] =
    cyclesFor(valid, latestCycle).iterator.flatMap { case (cycle, lead) =>
      scala.util.Try(table.levelsHpa.map(hPa =>
        decodePng(Http.getBytes(url(cycle, lead, hPa), attempts = 2), throttle)
      )).toOption
        .map(cycle -> _)
    }.nextOption()

  private lazy val ramp: Vector[(Int, Int, Int)] = table.ramp.map(c => (c(0), c(1), c(2)))

  /** Ramp step for a colour: 0 for clear (transparent or white), else the nearest ramp colour (1-based). */
  private[turbulence] def step(argb: Int): Int = {
    val a = (argb >>> 24) & 0xff
    val r = (argb >> 16) & 0xff
    val g = (argb >> 8) & 0xff
    val b = argb & 0xff
    if (a < 128 || (r == 255 && g == 255 && b == 255)) 0
    else 1 + ramp.indices.minBy { k =>
      val (rr, gg, bb) = ramp(k)
      (r - rr) *
        (r - rr) +
        (g - gg) *
        (g - gg) +
        (b - bb) *
        (b - bb)
    }
  }

  /** One WAFS image as encoded EDR on the GFS grid (rows from 90N, columns from 0E), nearest pixel. */
  def decodePng(bytes: Array[Byte], throttle: Throttle = Throttle.off): Array[Byte] = {
    val image = ImageIO.read(new ByteArrayInputStream(bytes))
    if (image == null) throw RuntimeException("not an image")
    val w = image.getWidth
    val h = image.getHeight
    val stepEdr = table.stepEdr
    val edrOf = scala.collection.mutable.HashMap.empty[Int, Byte]
    val top = log(tan(Pi / 4 + toRadians(table.maxLat) / 2))
    val ys = Array.tabulate(GlobalGrid.ny) { j =>
      val lat = GlobalGrid.latOf(j)
      if (abs(lat) >= table.maxLat) -1
      else min(h - 1, max(0, floor((top - log(tan(Pi / 4 + toRadians(lat) / 2))) / (2 * top) * h).toInt))
    }
    val xs = Array.tabulate(GlobalGrid.nx) { i =>
      val lon = GlobalGrid.lonOf(i)
      val lon180 = if (lon >= 180) lon - 360 else lon
      min(w - 1, max(0, floor((lon180 + 180) / 360 * w).toInt))
    }
    val out = new Array[Byte](GlobalGrid.nx * GlobalGrid.ny)
    // Reading a row at a time and remembering the last colour keeps this from allocating per pixel: getRGB(x, y) and
    // boxing every colour for the lookup made each hour's 8 images allocate 440 MB.
    val row = new Array[Int](w)
    var rowY = -1
    var lastArgb = 0
    var lastEdr = edrOf.getOrElseUpdate(0, EdrLayer.encode(stepEdr(min(step(0), stepEdr.size - 1)).toFloat))
    var j = 0
    while (j < GlobalGrid.ny) {
      throttle.tick()
      val y = ys(j)
      if (y >= 0 && y != rowY) { image.getRGB(0, y, w, 1, row, 0, w): Unit; rowY = y }
      var i = 0
      while (i < GlobalGrid.nx) {
        out(j * GlobalGrid.nx + i) =
          if (y < 0) EdrLayer.missing
          else {
            val argb = row(xs(i))
            if (argb != lastArgb) {
              lastArgb = argb
              lastEdr = edrOf.getOrElseUpdate(argb, EdrLayer.encode(stepEdr(min(step(argb), stepEdr.size - 1)).toFloat))
            }
            lastEdr
          }
        i += 1
      }
      j += 1
    }
    out
  }

  /** Where `levelFt` falls among the WAFS levels: (lower level index, weight of the upper one), held at the ends. */
  def bracket(levelFt: Int): (Int, Double) = {
    val fls = table.levelsFl
    val fl = min(max(levelFt / 100.0, fls.head.toDouble), fls.last.toDouble)
    val k = min(fls.lastIndexWhere(_ <= fl), fls.size - 2)
    (k, (fl - fls(k)) / (fls(k + 1) - fls(k)))
  }

  private lazy val tailSlopes: Vector[Double] = table.mixed.indices.toVector.map { k =>
    val x = table.mixed(k)
    val y = table.edr(k)
    val n = x.size
    log(y(n - 1) / y(n - 4)) / log(x(n - 1) / x(n - 4))
  }

  /** Index into the blend tables for a GTG level, if WAFS covers it. */
  def blendLevel(levelFt: Int): Option[Int] = Option(table.levelsFt.indexOf(levelFt)).filter(_ >= 0)

  /** Final EDR from the WAFS EDR and the TI1 EDR at the `level`-th blend level. */
  def blend(level: Int, wafsEdr: Double, ti1Edr: Double): Double = {
    val m = table.weight * wafsEdr + (1 - table.weight) * ti1Edr
    val x = table.mixed(level)
    val y = table.edr(level)
    if (m > x.last) min(1.0, y.last * pow(m / x.last, tailSlopes(level)))
    else if (m <= x.head) y.head
    else {
      var k = 1
      while (x(k) < m) k += 1
      y(k - 1) + (m - x(k - 1)) / (x(k) - x(k - 1)) * (y(k) - y(k - 1))
    }
  }
}
