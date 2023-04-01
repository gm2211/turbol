/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints

import cats.data.Kleisli
import cats.effect.IO
import com.gm2211.turbol.backend.objects.api.airports.search.{AirportSearchRequest, AirportSearchResponse}
import com.gm2211.turbol.backend.objects.api.airports.{Airport, IATACode, ICAOCode}
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import com.gm2211.turbol.backend.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceSensitiveDataEntityDecoder.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpRoutes, Request, Response}
import zio.*
import zio.interop.catz.*
import zio.interop.catz.implicits.*

import scala.language.implicitConversions

object AirportsEndpoint extends Endpoint with BackendSerialization {
  override val basePath: String = "/airports"
  override val routes: HttpRoutes[AppTask] = HttpRoutes.of[AppTask] { case req @ POST -> Root / "search" =>
    for
      // Decode a User request
      searchRequest: AirportSearchRequest <- req.as[AirportSearchRequest]
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
    yield resp
  }
}
