package com.gm2211.turbol.backend.objects.internal.storage.airports

case class AirportRow(
  id: Int,
  displayId: String,
  airportType: String,
  name: String,
  latitudeDeg: Double,
  longitudeDeg: Double,
  isoCountry: Option[String],
  icaoCode: Option[String],
  iataCode: Option[String],
  localCode: Option[String]
) {}
