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
import com.gm2211.turbol.backend.config.{ConfigWatcher, InstallConfig, RuntimeConfig}
import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.RuntimeEnv
import com.gm2211.turbol.backend.util.{ConfigSerialization, MoreSchedulers}
import io.circe.derivation.Configuration
import io.circe.{Decoder, Encoder}
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.{EntityDecoder, EntityEncoder}
import zio.interop.catz.*
import zio.logging.LogFormat
import zio.logging.backend.SLF4J
import zio.stream.ZStream
import zio.{Queue, Ref, Runtime, Scope, ULayer, Unsafe, ZEnvironment, ZIO, ZIOAppArgs, ZLayer}

import java.nio.file.Paths
import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Success
import zio.interop.catz.*

object Launcher extends CatsApp with ConfigSerialization {

  override val runtime: zio.Runtime[Any] = Runtime.default.withEnvironment(ZEnvironment())

//  override val bootstrap: zlayer[any, any, unit] =
//    zio.runtime.removedefaultloggers >>> slf4j.slf4j(logformat.line + logformat.cause)

  private val appLayer: ULayer[RuntimeEnv] = ZLayer.make[RuntimeEnv](
    Scope.default,
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
        _ <- ZIO.logDebug("Constructing config layer")
        loaded <- ZIO
          .fromTry(Source.fromFile("/tmp/install.yml").fromYaml[InstallConfig])
          .mapError(e => new IllegalArgumentException("Cannot read install config", e))
      yield loaded
    ).catchAll(e => ZIO.die(e))
  }
}
