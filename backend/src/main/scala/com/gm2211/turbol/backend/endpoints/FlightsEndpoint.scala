/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints

import cats.effect.IO
import com.gm2211.turbol.backend.objects.api.flights.{FlightNumber, FlightPlan}
import com.gm2211.turbol.backend.objects.api.location.GPSLocation
import com.gm2211.turbol.backend.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

import scala.language.implicitConversions

object FlightsEndpoint extends Endpoint with BackendSerialization {
  override val basePath: String = "/flights"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / flightNumber / "plan" =>
    // List of tuples representing the flight path for nyc to london
    val flightPath: List[GPSLocation] = List(
      (40.71, -74.00),
      (41.98, -87.90),
      (47.45, -122.30),
      (37.77, -122.41),
      (51.47, -0.45)
    )
    val flightPlan = FlightPlan(FlightNumber(flightNumber), flightPath)
    Ok(flightPlan.toJson.noSpaces)
  }
}
