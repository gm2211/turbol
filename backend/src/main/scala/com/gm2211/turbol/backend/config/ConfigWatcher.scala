/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config

import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.*
import com.gm2211.reactive.Types.Scheduler
import com.gm2211.turbol.backend.util.{BackendSerialization, ConfigSerialization, TryUtils}
import com.sun.nio.file.SensitivityWatchEventModifier
import io.circe.Decoder

import java.nio.file
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent.Kind
import java.nio.file.{FileSystems, Path, WatchKey, WatchService}
import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

object ConfigWatcher extends BackendLogging with ConfigSerialization with TryUtils {

  /**
   * Reads the contents of the config file at the provided path. It will also start monitoring for changes to the file,
   * reloading it as necessary. Changes to the runtime config after the server has started will be propagated
   * throughout the server.
   */
  def watchConfig[T, R](path: Path, initialValue: => T)(using scheduler: Scheduler)(using d: Decoder[T])
    : Refreshable[T] = {
    val configDirPath = path.getParent

    val watchService: WatchService = FileSystems.getDefault.newWatchService()
    configDirPath.register(watchService, Array[Kind[_]](ENTRY_MODIFY), SensitivityWatchEventModifier.HIGH)

    val configRef: Refreshable[T] = Refreshable(initialValue)

    scheduler.execute { () =>
      while (true) {
        Try {
          val watchKey: WatchKey = watchService.take()
          watchKey.pollEvents()
          watchKey.reset()
          log.info("Detected change in config dir", safe("configDirPath", configDirPath))
          readConfig(path) match
            case Failure(exception) =>
              log.info("Failed to read config, will leave existing config present", exception, safe("configPath", path))
            case Success(config) =>
              configRef.update(config)
              log.info("Successfully updated config", safe("configPath", path))
        }.ifFailure(exception => log.info("Unhandled error while monitoring config", exception))
      }
    }

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
