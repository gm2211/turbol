/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

import cats.effect.IO
import com.gm2211.logging.BackendLogging
import retry.{retryingOnFailuresAndAllErrors, RetryPolicies}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

trait BackgroundJob extends BackendLogging {
  def run(): IO[Unit]
  def runForever(ioExecutor: ExecutionContext): IO[Unit] = retryingOnFailuresAndAllErrors.apply[IO, Throwable](
    RetryPolicies.constantDelay[IO](1.minute),
    _ => IO.pure(true),
    (failure, details) => {
      log.warn("BG job execution failure", unsafe("failure", failure), safe("details", details))
      IO.unit
    },
    (error, details) => {
      log.warn("BG job execution error", unsafe("error", error), safe("details", details))
      IO.unit
    }
  )(run())
    .evalOn(ioExecutor)
    .map(_ => ())
}
