/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.server

import com.gm2211.turbol.backend.config.install.InstallConfig
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import org.http4s.dsl.Http4sDsl
import zio.RIO

object RuntimeEnvTypes {
  type RuntimeEnv = InstallConfig
  type AppTask[T] = RIO[RuntimeEnv, T]
}
