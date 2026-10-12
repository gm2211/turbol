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

  /**
   * The projection split into its latitude-only and longitude-only parts, so a map tile can compute 256 of each instead
   * of 65,536 full projections: `nearestIndex(rhoAt(lat), sin(thetaAt(lon)), cos(thetaAt(lon)))` equals
   * `nearestIndex(lat, lon)` for 0 <= lat <= 89.
   */
  def rhoAt(lat: Double): Double = earthRadius * f / pow(tan(Pi / 4 + toRadians(lat) / 2), n)
  def thetaAt(lon: Double): Double = {
    var dLon = toRadians(lon) - centralMeridian
    if (dLon > Pi) dLon -= 2 * Pi
    if (dLon < -Pi) dLon += 2 * Pi
    n * dLon
  }
  def nearestIndex(rho: Double, sinTheta: Double, cosTheta: Double): Int = {
    val i = rint((rho * sinTheta - x0) / dx).toInt
    val j = rint((rho0 - rho * cosTheta - y0) / dx).toInt
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

  /**
   * Grid cells over which GTG fades into the coarse global layer at the edge of its domain (40 cells = 120 km, about
   * four global grid steps), so the two don't meet in a hard seam.
   */
  val blendCells: Double = 40.0

  /** Distance in grid cells from fractional grid coordinates to the nearest edge of the grid (negative outside). */
  def edgeDistance(fi: Double, fj: Double): Double = min(min(fi, nx - 1 - fi), min(fj, ny - 1 - fj))

  /** GTG's weight against the global layer: 1 at least [[blendCells]] inside the grid, falling to 0 at its edge. */
  def interiorWeight(fi: Double, fj: Double): Double = max(0.0, min(1.0, edgeDistance(fi, fj) / blendCells))

  def interiorWeightAt(lat: Double, lon: Double): Double =
    if (lat < 0 || lat > 89) 0.0 else { val (fi, fj) = fractionalIndex(lat, lon); interiorWeight(fi, fj) }

  /** Fractional grid coordinates from the split projection, like the three-argument nearestIndex. */
  def fractionalIndex(rho: Double, sinTheta: Double, cosTheta: Double): (Double, Double) =
    ((rho * sinTheta - x0) / dx, (rho0 - rho * cosTheta - y0) / dx)

  /** The grid's edge as a closed [lat, lon] ring, one point every `every` cells, for drawing GTG's coverage. */
  def outline(every: Int = 60): Seq[(Double, Double)] = {
    val south = (0 until nx - 1 by every).map(i => latLonAt(i, 0))
    val east = (0 until ny - 1 by every).map(j => latLonAt(nx - 1, j))
    val north = (nx - 1 until 0 by -every).map(i => latLonAt(i, ny - 1))
    val west = (ny - 1 until 0 by -every).map(j => latLonAt(0, j))
    (south ++ east ++ north ++ west) :+ latLonAt(0, 0)
  }

  /** Approximate coverage, for cheap "is this anywhere near CONUS" checks. */
  val boundingBox: (Double, Double, Double, Double) = (21.1, -134.1, 52.7, -60.9) // south, west, north, east
}
