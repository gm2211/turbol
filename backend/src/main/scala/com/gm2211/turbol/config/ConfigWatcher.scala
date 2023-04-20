/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.config

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.*
import com.gm2211.turbol.util.{BackendSerialization, ConfigSerialization, TryUtils}
import com.sun.nio.file.SensitivityWatchEventModifier
import fs2.io.file.Path.fromNioPath
import fs2.io.file.Watcher
import io.circe.Decoder
import retry.{RetryPolicies, retryingOnAllErrors}

import java.nio.file
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent.Kind
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object ConfigWatcher extends BackendLogging with ConfigSerialization with TryUtils {

  /**
   * Reads the contents of the config file at the provided path. It will also start monitoring for changes to the file,
   * reloading it as necessary. Changes to the runtime config after the server has started will be propagated
   * throughout the server.
   */
  def watchConfig[T](configPath: Path, initialValue: => T)
    (using IORuntime)
    (using Decoder[T])
  : Refreshable[T] = {
    val configDirPath = configPath.getParent

    val watchService: WatchService = FileSystems.getDefault.newWatchService()
    configDirPath.register(watchService, Array[Kind[_]](ENTRY_MODIFY), SensitivityWatchEventModifier.HIGH)

    val configRef: Refreshable[T] = Refreshable(initialValue)

    retryingOnAllErrors(
      policy = RetryPolicies.constantDelay[IO](0.seconds),
      onError = (error: Throwable, details: retry.RetryDetails) => {
        IO.println(s"Failed to read config, will retry $error ${details}")
      }
    ) {
      IO.blocking {
        while (true) {
          Try {
            val watchKey: WatchKey = watchService.take()
            val watchedFiledChanged = watchKey.pollEvents().asScala.exists(_.context() == configPath.getFileName)
            watchKey.reset()
            log.info("Detected change in config dir", safe("configDirPath", configDirPath))
            if (watchedFiledChanged) {
              log.info("Detected change in config file", safe("configPath", configPath))
              readConfig(configPath) match
                case Failure(exception) =>
                  log.info(
                    "Failed to read config, will leave existing config present",
                    exception,
                    safe("configPath", configPath)
                  )
                case Success(config) =>
                  configRef.update(config)
                  log.info("Successfully updated config", safe("configPath", configPath))
            }
          }.ifFailure(exception => log.info("Unhandled error while monitoring config", exception))
        }
      }
    }
      .background
      .useForever
      .unsafeRunAndForget()

    configRef
  }

  private def readConfig[T: Decoder](path: Path): Try[T] = {
    path
      .toFile
      .fromYaml
      .ifSuccess(_ => log.info("Successfully read config", safe("configPath", path)))
      .ifFailure(error => log.info(s"Failed to read config", error, safe("configPath", path)))
  }
}
