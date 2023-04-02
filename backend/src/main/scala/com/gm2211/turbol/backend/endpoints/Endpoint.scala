/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.endpoints
import cats.effect.IO
import com.gm2211.turbol.backend.config.install.InstallConfig
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import zio.{Clock, RIO, ZEnvironment}

trait Endpoint extends Http4sDsl[AppTask] {
  val basePath: String
  val routes: HttpRoutes[AppTask]
}
