/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config.install

import com.gm2211.turbol.backend.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder
import zio.{ULayer, ZIO, ZLayer}

case class InstallConfig(server: ServerConfig = ServerConfig())

object InstallConfig extends ConfigSerialization {
  given Decoder[InstallConfig] = ConfiguredDecoder.derived[InstallConfig]
}

case class ServerConfig(
  devMode: Boolean = false,
  port: Int = 8081
)
object ServerConfig extends ConfigSerialization {
  given Decoder[ServerConfig] = ConfiguredDecoder.derived[ServerConfig]
}
