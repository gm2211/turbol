/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.airports

import doobie.util.Read

case class AirportRow(
  icaoCode: String,
  iataCode: String,
  airportName: Option[String],
  airportType: Option[String],
  latitudeDeg: Option[Double],
  longitudeDeg: Option[Double],
  isoCountry: Option[String],
  localCode: Option[String],
  keywords: List[String]
) {}

object AirportRow {
  // Allows reading a row from the database without reading the 'keywords' field
  given Read[AirportRow] =
    Read[(
      String,
      String,
      Option[String],
      Option[String],
      Option[Double],
      Option[Double],
      Option[String],
      Option[String]
    )].map {
      case (icaoCode, iataCode, airportName, airportType, latitudeDeg, longitudeDeg, isoCountry, localCode) =>
        AirportRow(
          icaoCode = icaoCode,
          iataCode = iataCode,
          airportName = airportName,
          airportType = airportType,
          latitudeDeg = latitudeDeg,
          longitudeDeg = longitudeDeg,
          isoCountry = isoCountry,
          localCode = localCode,
          keywords = List()
        )
    }
}
