package com.gm2211.turbol.backend.server

import com.gm2211.turbol.backend.config.InstallConfig
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import org.http4s.dsl.Http4sDsl
import zio.RIO

object RuntimeEnvTypes {
  type RuntimeEnv = InstallConfig
  type AppTask[T] = RIO[RuntimeEnv, T]
  
  object Http4sZioDsl extends Http4sDsl[AppTask]
}
