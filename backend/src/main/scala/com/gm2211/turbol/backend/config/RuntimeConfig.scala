/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config

import com.gm2211.turbol.backend.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

case class RuntimeConfig(logging: LoggingConfig = LoggingConfig())

object RuntimeConfig extends ConfigSerialization {
  given decoder: Decoder[RuntimeConfig] = ConfiguredDecoder.derived[RuntimeConfig]
}
