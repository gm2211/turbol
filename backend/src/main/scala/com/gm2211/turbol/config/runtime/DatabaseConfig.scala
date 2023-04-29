/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.config.runtime

import com.gm2211.turbol.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

case class DatabaseConfig(
  adminUser: String = "postgres",
  databaseName: String = "dev",
  databaseType: DBType = Postgres,
  hostname: String = "postgresql",
  port: Int = 5432
)

object DatabaseConfig extends ConfigSerialization {
  given decoder: Decoder[DatabaseConfig] = ConfiguredDecoder.derived[DatabaseConfig]
}

sealed trait DBType
case object Postgres extends DBType
