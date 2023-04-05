/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import cats.effect.{Async, ExitCode, IO, IOApp}
import cats.implicits.*
import com.comcast.ip4s.Port
import com.gm2211.turbol.backend.config.ConfigWatcher
import com.gm2211.turbol.backend.config.install.InstallConfig
import com.gm2211.turbol.backend.config.runtime.RuntimeConfig
import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.RuntimeEnv
import com.gm2211.turbol.backend.util.{ConfigSerialization, MoreExecutors}
import io.circe.derivation.Configuration
import io.circe.{Decoder, Encoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder}
import zio.*
import zio.interop.catz.*
import zio.logging.LogFormat
import zio.logging.backend.SLF4J
import zio.stream.ZStream

import java.nio.file.Paths
import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Success

object Launcher extends CatsApp with ConfigSerialization {

  override val runtime: zio.Runtime[Any] = Runtime.default.withEnvironment(ZEnvironment())

  override val bootstrap: ZLayer[Any, Any, Unit] =
    zio.Runtime.removeDefaultLoggers >>> SLF4J.slf4j(LogFormat.line + LogFormat.cause)

  private val appLayer: ULayer[RuntimeEnv] = ZLayer.make[RuntimeEnv](
    installConfig
  )

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] = {
    def appServer: ZIO[RuntimeEnv, Serializable, Nothing] =
      for
        install <- ZIO.service[InstallConfig]
        server <- AppServer.createServer(install)
      yield server

    ZIO.logInfo("Turbol server starting up..") *> appServer
      .provideLayer(appLayer)
      .catchAll(error => ZIO.logError(s"Unhandled error $error"))
      .tapDefect(throwable => ZIO.logError(s"Fatal error, app will crash: $throwable"))
      .exitCode
  }

  private def installConfig: ULayer[InstallConfig] = ZLayer.fromZIO {
    (
      for
        _ <- ZIO.logDebug("Reading install config")
        installConfigPath <- System.env("INSTALL_CONFIG_OVERRIDES_PATH").mapAttempt(_.get)
        loaded <- ZIO
          .fromTry[InstallConfig](Source.fromFile(installConfigPath).fromYaml[InstallConfig])
          .mapError(e => new IllegalArgumentException(s"Cannot read install config at $installConfigPath\n", e))
      yield loaded
    ).catchAll(e => ZIO.die(e))
  }
}
