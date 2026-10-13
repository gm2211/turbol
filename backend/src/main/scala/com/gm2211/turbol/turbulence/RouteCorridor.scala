/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.time.Instant

/** A route point to build the corridor around: where and when the flight passes, as in a [[RoutePoint]]. */
final case class CorridorPoint(lat: Double, lon: Double, altitudeFt: Double, time: Instant)

/**
 * The turbulence around a flight: at each route point, a cross-section of levels above and below it and offsets to
 * either side of its track, all sampled when the flight passes that point, the way the analysis samples the route
 * itself. Level 0, offset 0 is the route's own sample, so the flight and the air around it always agree.
 *
 * @param levelsFt
 *   altitude shifts from the flight's own altitude, lowest first
 * @param offsetsKm
 *   distances to the right of the track (negative: left), leftmost first
 * @param edr
 *   EDR bytes ([[EdrLayer.encode]], 255: no data) by point, then level, then offset
 */
final case class Corridor(levelsFt: Vector[Int], offsetsKm: Vector[Double], edr: Array[Byte])

object RouteCorridor {
  /** 6,000 ft above and below in 1,000 ft steps: the native GTG levels, and more than a flight would change by. */
  val levelsFt: Vector[Int] = (-6 to 6).map(_ * 1000).toVector

  /** 60 km either side in 15 km steps: about a GTG block, out to a slight detour. */
  val offsetsKm: Vector[Double] = (-4 to 4).map(_ * 15.0).toVector

  /** Requests are capped: the analysis samples at most 500 points. */
  val maxPoints: Int = 600

  def build(store: TurbulenceStore, points: Seq[CorridorPoint]): Corridor = {
    val data = store.current
    val pts = points.take(maxPoints).toIndexedSeq
    val nl = levelsFt.length
    val no = offsetsKm.length
    val out = new Array[Byte](pts.length * nl * no)
    pts.indices.foreach { k =>
      val p = pts(k)
      // Track direction from the neighbours, so the cross-section is square to where the flight is going.
      val a = pts(math.max(k - 1, 0))
      val b = pts(math.min(k + 1, pts.length - 1))
      val track =
        if (a == b) 0.0 else RouteAnalyzer.bearing(a.lat, a.lon, b.lat, b.lon)
      offsetsKm.indices.foreach { o =>
        val d = offsetsKm(o)
        val (lat, lon) =
          if (d == 0) (p.lat, p.lon) else RouteAnalyzer.destination(p.lat, p.lon, track + 90, d)
        val column = store.sampleColumnIn(data, lat, lon, levelsFt.map(p.altitudeFt + _), p.time)
        levelsFt.indices.foreach { l =>
          val edr = if (p.altitudeFt + levelsFt(l) < 0) Double.NaN else column(l)
          out((k * nl + l) * no + o) = EdrLayer.encode(edr.toFloat)
        }
      }
    }
    Corridor(levelsFt, offsetsKm, out)
  }
}
