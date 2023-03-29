/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.config

import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.util.{BackendSerialization, ConfigSerialization, TryUtils}
import io.circe.Decoder
import zio.*
import zio.managed.ZManaged

import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchEvent.Kind
import java.nio.file.{FileSystems, Path, WatchKey, WatchService}
import scala.util.{Failure, Success, Try}

class ConfigWatcher[T: Decoder] extends BackendLogging with ConfigSerialization with TryUtils {

  /** Reads the contents of the config file at the provided path. It will also start monitoring for changes to the file,
    * reloading it as necessary. Changes to the runtime config after the server has started will be propagated
    * throughout the server.
    */
  def watchConfig(path: Path, orDefault: => T): Ref[T] = {
    val configDirPath = path.getParent

    val createWatcher: ZIO[Any, Throwable, WatchService] = for
      watcher <- ZIO.attempt(FileSystems.getDefault.newWatchService())
      _ <- ZIO.log(s"Registering path with watcher $configDirPath")
      _ <- ZIO.attempt(
        configDirPath.register(
          watcher,
          Array[Kind[_]](ENTRY_MODIFY)
        )
      )
    yield watcher

    val configRef: Ref[T] =
      Unsafe.unsafe { implicit unsafe =>
        val ref: UIO[Ref[T]] = for
          initialConfig: T <- readConfig(path).orElse(ZIO.succeed(orDefault))
          configRef <- Ref.make(initialConfig)
        yield configRef

        Runtime.default.unsafe.run(ref).getOrThrowFiberFailure()
      }

    val watcherLoop: Task[Unit] = for
      watcher <- createWatcher.retry(Schedule.forever).memoize.flatten
      watchKey <- ZIO.attempt(watcher.take())
      _ <- ZIO.attempt(watchKey.pollEvents())
      _ <- ZIO.attempt(watchKey.reset())
      _ <- ZIO.log("Detected change in config dir")
      config <- readConfig(path)
      _ <- configRef.set(config)
    yield ()

    watcherLoop
      .repeat(Schedule.forever)
      .forkDaemon

    configRef
  }

  private def readConfig(path: Path): Task[T] = {
    ZIO.fromTry(
      path
        .toFile
        .fromYaml
        .ifSuccess(_ => log.info("Successfully read config", safe("configPath", path)))
        .ifFailure(error => log.info(s"Failed to read config", error, safe("configPath", path)))
    )
  }
}
