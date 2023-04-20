/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints
import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

trait Endpoint extends Http4sDsl[IO] {
  val basePath: String
  val routes: HttpRoutes[IO]
}
