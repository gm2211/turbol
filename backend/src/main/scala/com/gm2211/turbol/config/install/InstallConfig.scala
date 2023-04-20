/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.config.install

import com.gm2211.turbol.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

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
