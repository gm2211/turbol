package com.gm2211.turbol.modules

import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.install.InstallConfig
import com.gm2211.turbol.config.runtime.{DatabaseConfig, RuntimeConfig}
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.util.MoreExecutors
import com.gm2211.turbol.util.MoreExecutors.{*, given}
import com.softwaremill.macwire.Module

@Module
class ConfigModule(
  val install: InstallConfig,
  val runtime: Refreshable[RuntimeConfig],
  val appSecrets: Refreshable[AppSecrets]
) {
  lazy val dbConfig: Refreshable[DatabaseConfig] =
    runtime.map(_.databaseConfig)(using MoreExecutors.fixed("db-config", 1))
}
