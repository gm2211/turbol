/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.objects.api.airports.search.{AirportSearchRequest, AirportSearchResponse}
import com.gm2211.turbol.objects.api.airports.{Airport, IATACode, ICAOCode}
import com.gm2211.turbol.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder
import org.http4s.dsl.io.*
import org.http4s.{HttpRoutes, Request, Response}

object AirportsEndpoint extends Endpoint with BackendSerialization {
  override val basePath: String = "/airports"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case req @ POST -> Root / "search" =>
    for {
      // Decode a User request
      searchRequest: IO[AirportSearchRequest] <- IO(req.as[AirportSearchRequest])
      resp <- Ok(
        AirportSearchResponse(
          List(
            Airport(
              name = "Fiumicino",
              city = "Rome",
              country = "Italy",
              iata = IATACode("LIRF"),
              icao = ICAOCode("LIRF"),
              location = (41.8002778, 12.2388889)
            )
          )
        ).toJson
      )
    } yield resp
  }
}
