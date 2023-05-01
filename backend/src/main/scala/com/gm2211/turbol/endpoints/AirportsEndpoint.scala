/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.objects.api.airports.search.{AirportSearchRequest, AirportSearchResponse}
import com.gm2211.turbol.objects.api.airports.{Airport, IATACode, ICAOCode}
import com.gm2211.turbol.services.AirportsService
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Json
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request, Response}

final class AirportsEndpoint(airportsService: AirportsService) extends Endpoint with BackendSerialization {
  override val basePath: String = "/airports"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "search" =>
    val responseJson: IO[Json] = for {
      // Decode a User request
      searchRequest: AirportSearchRequest <- req.as[AirportSearchRequest]
      airports <- IO.fromTry { airportsService.search(searchRequest.query) }
      resp = AirportSearchResponse(airports.toList.map(AirportsEndpoint.toApi))
    } yield resp.toJson
    Ok(responseJson)
  }
}
object AirportsEndpoint {
  def toApi(airport: com.gm2211.turbol.objects.internal.model.airports.Airport): Airport =
    Airport(
      name = airport.name,
      city = airport.city,
      country = airport.country,
      iata = IATACode(airport.iata.toString),
      icao = ICAOCode(airport.icao.toString),
      location = airport.location
    )
}
