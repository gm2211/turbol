/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol

import cats.effect.unsafe.IORuntime
import cats.effect.{ExitCode, IO, IOApp}
import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.ConfigWatcher
import com.gm2211.turbol.config.install.InstallConfig
import com.gm2211.turbol.config.runtime.RuntimeConfig
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.modules.*
import com.gm2211.turbol.util.{ConfigSerialization, MoreExecutors, OptionUtils, TryUtils}

import java.nio.file.{Path, Paths}
import scala.io.Source

object Launcher extends IOApp with ConfigSerialization with OptionUtils with TryUtils with BackendLogging {
  override def run(args: List[String]): IO[ExitCode] = {
    val installConfigPath: Path = readPathFromEnv("INSTALL_CONFIG_OVERRIDES_PATH", "install config path")
    val appSecretsPath: Path = readPathFromEnv("APP_SECRETS_PATH", "app secrets path")
    val runtimeConfigPath: Path =
      readPathFromEnv("RUNTIME_CONFIG_OVERRIDES_PATH", "runtime config path")

    val install: InstallConfig = Source
      .fromFile(installConfigPath.toFile)
      .fromYaml[InstallConfig]
      .getOrThrow(e => new IllegalArgumentException(s"Cannot read install config at $installConfigPath\n", e))
    val appSecrets: Refreshable[AppSecrets] =
      ConfigWatcher.watchConfig(appSecretsPath)(using IORuntime.global)
    val runtime: Refreshable[RuntimeConfig] =
      ConfigWatcher.watchConfig(runtimeConfigPath)(using IORuntime.global)

    val configModule: ConfigModule = ConfigModule(install, runtime, appSecrets)
    val storageModule = StorageModule(configModule)
    val servicesModule = ServicesModule(storageModule)
    val appModule: AppModule = AppModule(
      backgroundJobsModule = BackgroundJobsModule(storageModule, servicesModule),
      configModule = configModule,
      endpointsModule = EndpointsModule(servicesModule, configModule),
      servicesModule = servicesModule,
      storageModule = storageModule
    )
    val appServer = AppServer.createServer(appModule)

    appModule.storageModule.txnManager.awaitInitialized()

    appServer
      .use(_ =>
        for {
          _ <-
            appModule.backgroundJobsModule.airportDataUpdater.runForever(MoreExecutors.fixed("bg", 1).executionContext)
          _ <- IO.never
        } yield ()
      )
      .as(ExitCode.Success)
  }
  private def readPathFromEnv(envVarName: String, envVarDescription: String) = {
    Paths.get(sys.env.get(envVarName).orThrow("Cannot read " + envVarDescription + " from env"))
  }
}
