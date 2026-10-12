/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import scala.math.*

/**
 * The GFS 0.25 degree global latitude/longitude grid: 1440 x 721 points, rows from 90N southwards, columns from 0E
 * eastwards (GRIB scan order), so the flat index is row * nx + col. About 28 km between points at the equator, roughly
 * ten times coarser than the 3 km GTG grid.
 */
object GlobalGrid {
  val nx: Int = 1440
  val ny: Int = 721
  val resolutionDeg: Double = 0.25
  val resolutionKm: Double = 28.0

  /** Row of the nearest grid latitude. */
  def rowAt(lat: Double): Int = rint((90.0 - lat) / resolutionDeg).toInt

  /** Column of the nearest grid longitude, wrapping around the date line. */
  def colAt(lon: Double): Int = {
    val c = rint(lon / resolutionDeg).toInt % nx
    if (c < 0) c + nx else c
  }

  def latOf(row: Int): Double = 90.0 - row * resolutionDeg
  def lonOf(col: Int): Double = { val lon = col * resolutionDeg; if (lon > 180) lon - 360 else lon }

  /** Flat index of the nearest grid point, or -1 for an invalid latitude. */
  def nearestIndex(lat: Double, lon: Double): Int =
    if (lat.isNaN || lon.isNaN || lat < -90 || lat > 90) -1 else rowAt(lat) * nx + colAt(lon)
}
