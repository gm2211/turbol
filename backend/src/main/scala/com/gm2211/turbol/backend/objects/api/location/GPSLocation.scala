package com.gm2211.turbol.backend.objects.api.location

case class GPSLocation(lat: Double, lon: Double)
object GPSLocation {
  given Conversion[(Double, Double), GPSLocation] = (latLon: (Double, Double)) => GPSLocation(latLon._1, latLon._2)
}
