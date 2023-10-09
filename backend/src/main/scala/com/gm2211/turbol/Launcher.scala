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
  private val defaultPlainConfDir = "/etc/conf/plain"
  private val defaultSecretsDir = "/etc/conf/secrets"

  override def run(args: List[String]): IO[ExitCode] = {
    val installConfigPath: Path =
      readPathFromEnv("INSTALL_CONFIG_OVERRIDES_PATH")
        .getOrElse(Paths.get(s"$defaultPlainConfDir/install.yml"))
    val appSecretsPath: Path = readPathFromEnv("APP_SECRETS_PATH")
      .getOrElse(Paths.get(s"$defaultSecretsDir/install-secrets.yml"))
    val runtimeConfigPath: Path =
      readPathFromEnv("RUNTIME_CONFIG_OVERRIDES_PATH")
        .getOrElse(Paths.get(s"$defaultPlainConfDir/runtime.yml"))

    val install: InstallConfig = Source
      .fromFile(installConfigPath.toFile)
      .fromYaml[InstallConfig]
      .getOrThrow(e => new IllegalArgumentException(s"Cannot read install config at $installConfigPath\n", e))
    val appSecrets: Refreshable[AppSecrets] =
      ConfigWatcher.watchConfig(appSecretsPath)(using IORuntime.global)
    val runtime: Refreshable[RuntimeConfig] =
      ConfigWatcher.watchConfig(runtimeConfigPath)(using IORuntime.global)

    val configModule: ConfigModule = ConfigModule(install, runtime, appSecrets)
    val envModule: EnvironmentModule = EnvironmentModule()
    val storageModule = StorageModule(configModule, envModule)
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
  private def readPathFromEnv(envVarName: String): Option[Path] = {
    val maybePath = sys.env.get(envVarName)
    maybePath.map(Paths.get(_))
  }
}
