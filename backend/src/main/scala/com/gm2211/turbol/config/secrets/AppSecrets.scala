/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.config.secrets

import com.gm2211.turbol.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

// Decided not to use Vault for now to keep things simple
case class AppSecrets(
  dbAdminPassword: String,
  mapboxToken: String
)
object AppSecrets extends ConfigSerialization {
  given Decoder[AppSecrets] = ConfiguredDecoder.derived[AppSecrets]
}
