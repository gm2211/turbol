/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import cats.effect.{ExitCode, IO, IOApp}
import com.gm2211.turbol.backend.config.InstallConfig
import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.util.MoreSchedulers

import java.util.concurrent.{Executors, ScheduledExecutorService}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

object Launcher extends IOApp with BackendLogging {
  override protected def runtime: IORuntime = createIORuntime()

  override def run(args: List[String]): IO[ExitCode] = {
    val installConfig = InstallConfig(devMode = true)

    AppServer(installConfig)
      .createServer
      .use(_server => IO.never /* never release server */ )
      .as(ExitCode.Success)
  }

  private def createIORuntime(
  ): IORuntime = {
    IORuntime
      .builder()
      .setScheduler(
        Scheduler.fromScheduledExecutor(
          Executors.newScheduledThreadPool(
            4,
            MoreSchedulers.threadFactory("main-scheduler")
          )
        ),
        (
        ) => log.info("Shutting down scheduler")
      )
      .setBlocking(
        ExecutionContext.fromExecutorService(
          MoreSchedulers.boundedCached("blocking", 100, 1.minute)
        ),
        (
        ) => log.info("Shutting down blocking scheduler")
      )
      .setCompute(
        ExecutionContext.fromExecutorService(
          MoreSchedulers.boundedCached("compute", 100, 1.minute)
        ),
        (
        ) => log.info("Shutting down compute")
      )
      .setFailureReporter(throwable => log.info("Unhandled error ", throwable))
      .addShutdownHook(
        (
        ) => log.info("Shutting down IO runtime")
      )
      .build()
  }

}
