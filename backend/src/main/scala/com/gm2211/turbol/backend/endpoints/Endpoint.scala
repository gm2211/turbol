package com.gm2211.turbol.backend.endpoints
import cats.effect.IO
import org.http4s.HttpRoutes

trait Endpoint {
  val basePath: String
  val routes: HttpRoutes[IO]
}
