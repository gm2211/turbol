/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints
import cats.effect.IO
import org.http4s.HttpRoutes

trait Endpoint {
  val basePath: String
  val routes: HttpRoutes[IO]
}
