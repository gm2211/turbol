package com.gm2211.turbol.backend.config.runtime

import com.gm2211.turbol.backend.util.ConfigSerialization
import io.circe.Decoder
import io.circe.derivation.ConfiguredDecoder

case class DatabaseConfig(
  adminUser: String = "postgres",
  databaseName: String = "dev",
  hostname: String = "postgresql",
  port: Int = 5432
)

object DatabaseConfig extends ConfigSerialization {
  given decoder: Decoder[DatabaseConfig] = ConfiguredDecoder.derived[DatabaseConfig]
}