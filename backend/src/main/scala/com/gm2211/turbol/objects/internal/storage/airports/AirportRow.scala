/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.airports

import com.gm2211.turbol.objects.api.location.GPSLocation
import com.gm2211.turbol.objects.internal.model.airports.{Airport, IATACode, ICAOCode}
import doobie.util.Read

case class AirportRow(
  icaoCode: String,
  iataCode: String,
  airportName: Option[String],
  airportType: Option[String],
  latitudeDeg: Option[Double],
  longitudeDeg: Option[Double],
  municipality: Option[String],
  isoCountry: Option[String],
  localCode: Option[String],
  keywords: List[String]
) {
  def toAirport: Airport = Airport(
    name = airportName.getOrElse(""),
    city = municipality.getOrElse(""),
    country = isoCountry.getOrElse(""),
    iata = IATACode(iataCode),
    icao = ICAOCode(icaoCode),
    location = GPSLocation(latitudeDeg.getOrElse(0), longitudeDeg.getOrElse(0))
  )
}

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
      Option[String],
      Option[String]
    )].map {
      case (
            icaoCode,
            iataCode,
            airportName,
            airportType,
            latitudeDeg,
            longitudeDeg,
            municipality,
            isoCountry,
            localCode
          ) =>
        AirportRow(
          icaoCode = icaoCode,
          iataCode = iataCode,
          airportName = airportName,
          airportType = airportType,
          latitudeDeg = latitudeDeg,
          longitudeDeg = longitudeDeg,
          municipality = municipality,
          isoCountry = isoCountry,
          localCode = localCode,
          keywords = List()
        )
    }
}
