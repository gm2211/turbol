/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.objects.flights

case class Waypoint(lat: Double, lon: Double)
object Waypoint {
  given Conversion[(Double, Double), Waypoint] = (w: (Double, Double)) => Waypoint(w._1, w._2)
}
