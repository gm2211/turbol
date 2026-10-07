/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import scala.math.*

/**
 * The HRRR CONUS Lambert Conformal grid that both GTGN (nowcast) and GTG v4 (forecast) use: 1799 x 1059 points at
 * 3 km, tangent at 38.5N, central meridian 97.5W, spherical earth (R = 6371229 m). Values verified from the NOMADS
 * files in data-pipeline/gtgn_common.py.
 *
 * Grid index (i, j) has i growing east and j growing north from the south-west corner, matching the GRIB scan order,
 * so the flat index is j * nx + i.
 */
object LambertGrid {
  val nx: Int = 1799
  val ny: Int = 1059
  val dx: Double = 3000.0
  private val earthRadius = 6371229.0
  private val standardParallel = toRadians(38.5)
  private val centralMeridian = toRadians(-97.5)
  private val firstLat = 21.138124
  private val firstLon = 237.28048 - 360.0

  private val n = sin(standardParallel)
  private val f = cos(standardParallel) * pow(tan(Pi / 4 + standardParallel / 2), n) / n
  private val rho0 = earthRadius * f / pow(tan(Pi / 4 + standardParallel / 2), n)

  private def project(lat: Double, lon: Double): (Double, Double) = {
    val rho = earthRadius * f / pow(tan(Pi / 4 + toRadians(lat) / 2), n)
    var dLon = toRadians(lon) - centralMeridian
    if (dLon > Pi) dLon -= 2 * Pi
    if (dLon < -Pi) dLon += 2 * Pi
    val theta = n * dLon
    (rho * sin(theta), rho0 - rho * cos(theta))
  }

  private val (x0, y0) = project(firstLat, firstLon)

  /** Fractional grid coordinates for a lat/lon. May be outside [0, nx) x [0, ny). */
  def fractionalIndex(lat: Double, lon: Double): (Double, Double) = {
    val (x, y) = project(lat, lon)
    ((x - x0) / dx, (y - y0) / dx)
  }

  /** Flat index of the nearest grid point, or -1 when the location is outside the grid. */
  def nearestIndex(lat: Double, lon: Double): Int = {
    if (lat < 0 || lat > 89) return -1
    val (fi, fj) = fractionalIndex(lat, lon)
    val i = rint(fi).toInt
    val j = rint(fj).toInt
    if (i < 0 || i >= nx || j < 0 || j >= ny) -1 else j * nx + i
  }

  /** Lat/lon of fractional grid coordinates: the inverse of [[fractionalIndex]]. */
  def latLonAt(fi: Double, fj: Double): (Double, Double) = {
    val x = x0 + fi * dx
    val y = rho0 - (y0 + fj * dx)
    val rho = signum(n) * sqrt(x * x + y * y)
    val theta = atan2(x, y)
    val lat = toDegrees(2 * atan(pow(earthRadius * f / rho, 1 / n)) - Pi / 2)
    val lon = toDegrees(centralMeridian + theta / n)
    (lat, lon)
  }

  /** Approximate coverage, for cheap "is this anywhere near CONUS" checks. */
  val boundingBox: (Double, Double, Double, Double) = (21.1, -134.1, 52.7, -60.9) // south, west, north, east
}
