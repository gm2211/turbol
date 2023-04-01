/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints
import cats.effect.IO
import com.gm2211.turbol.backend.config.InstallConfig
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import org.http4s.HttpRoutes
import zio.{Clock, RIO, ZEnvironment}

trait Endpoint {
  val basePath: String
  val routes: HttpRoutes[AppTask]
}
