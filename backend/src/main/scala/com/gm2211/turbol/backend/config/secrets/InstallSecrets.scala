/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config.secrets

import com.gm2211.turbol.backend.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

// Decided not to use Vault for now to keep things simple
case class InstallSecrets(
  postgresPassword: String
)
object InstallSecrets extends ConfigSerialization {
  given Decoder[InstallSecrets] = ConfiguredDecoder.derived[InstallSecrets]
}