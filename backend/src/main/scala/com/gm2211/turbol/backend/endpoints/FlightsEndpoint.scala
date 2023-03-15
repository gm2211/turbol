/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

object FlightsEndpoint extends Endpoint {
  override val basePath: String = "/flights"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / flightId =>
    Ok(flightId)
  }
}
