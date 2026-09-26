/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

import cats.effect.IO
import com.gm2211.logging.BackendLogging
import retry.{retryingOnErrors, ResultHandler, RetryPolicies}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

trait BackgroundJob extends BackendLogging {
  def run(): IO[Unit]
  def runForever(ioExecutor: ExecutionContext): IO[Unit] = retryingOnErrors(run())(
    RetryPolicies.constantDelay[IO](1.minute),
    ResultHandler.retryOnAllErrors[IO, Unit] { (error, details) =>
      IO(log.warn("BG job execution error", unsafe("error", error), safe("details", details)))
    }
  )
    .evalOn(ioExecutor)
}
