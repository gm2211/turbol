/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.airports

import doobie.util.Read

case class AirportRow(
  displayId: String,
  airportType: String,
  name: String,
  latitudeDeg: Double,
  longitudeDeg: Double,
  isoCountry: Option[String],
  icaoCode: String,
  iataCode: String,
  localCode: Option[String],
  keywords: List[String]
) {}

object AirportRow {
  // Allows reading a row from the database without reading the 'keywords' field
  given Read[AirportRow] =
    Read[(String, String, String, Double, Double, Option[String], String, String, Option[String])].map {
      case (displayId, airportType, name, latitudeDeg, longitudeDeg, isoCountry, icaoCode, iataCode, localCode) =>
        AirportRow(
          displayId,
          airportType,
          name,
          latitudeDeg,
          longitudeDeg,
          isoCountry,
          icaoCode,
          iataCode,
          localCode,
          List()
        )
    }
}
