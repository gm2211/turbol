/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import _root_.io.circe.derivation.Configuration
import _root_.io.circe.{Decoder, Encoder}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import cats.effect.{Async, ExitCode, IO, IOApp}
import cats.implicits.*
import com.comcast.ip4s.Port
import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.backend.config.ConfigWatcher
import com.gm2211.turbol.backend.config.install.InstallConfig
import com.gm2211.turbol.backend.config.runtime.RuntimeConfig
import com.gm2211.turbol.backend.util.MoreExecutors.*
import com.gm2211.turbol.backend.util.{ConfigSerialization, MoreExecutors, OptionUtils, TryUtils}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder}

import java.nio.file.Paths
import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Success

object Launcher extends IOApp with ConfigSerialization with OptionUtils with TryUtils with BackendLogging {
  override def run(args: List[String]): IO[ExitCode] = {
    val installConfigPath = sys.env.get("INSTALL_CONFIG_OVERRIDES_PATH").orThrow()
    val runtimeConfigPath = Paths.get(sys.env.get("RUNTIME_CONFIG_OVERRIDES_PATH").orThrow())

    val install: InstallConfig = Source
      .fromFile(installConfigPath)
      .fromYaml[InstallConfig]
      .getOrThrow(e => new IllegalArgumentException(s"Cannot read install config at $installConfigPath\n", e))
    val runtime =
      ConfigWatcher.watchConfig(runtimeConfigPath, RuntimeConfig.default)(using IORuntime.global)

    def appServer = AppServer.createServer(install, runtime)

    appServer
      .use(_ => IO.never)
      .as(ExitCode.Success)
  }
}
