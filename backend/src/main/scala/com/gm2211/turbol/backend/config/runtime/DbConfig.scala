/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config.runtime

import com.gm2211.turbol.backend.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

import scala.annotation.unused

case class DbConfig(
  hostname: String,
  port: Int,
  databaseName: String,
  adminUser: String
)
@unused // Used by circe
object DbConfig extends ConfigSerialization {
  given Decoder[DbConfig] = ConfiguredDecoder.derived[DbConfig]
}
