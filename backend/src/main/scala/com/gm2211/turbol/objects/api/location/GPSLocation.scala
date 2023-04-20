/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.api.location

case class GPSLocation(lat: Double, lon: Double)
object GPSLocation {
  given Conversion[(Double, Double), GPSLocation] = (latLon: (Double, Double)) => GPSLocation(latLon._1, latLon._2)
}
