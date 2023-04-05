/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import com.gm2211.turbol.backend.logging.BackendLogging
import com.gm2211.turbol.backend.util.MoreExecutors
import com.gm2211.turbol.backend.util.MoreExecutors.*
import zio.internal.Platform
import zio.{Executor, Fiber, Hub, Runtime, UIO, Unsafe, ZIO}

import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext

object ZIOUtils extends ZIOUtils // Allows .* imports
trait ZIOUtils extends BackendLogging {
  extension [R, E, T](zioVal: ZIO[R, E, T]) {
    def runUnsafe(using executorService: ExecutorService)(using runtime: Runtime[R]): T = {
      Unsafe.unsafely {
        runtime
          .unsafe
          .run(for {
            _ <- ZIO.succeed(()).onExecutor(Executor.fromJavaExecutor(executorService))
            _ <- ZIO.succeed(log.info("Running ZIO on executor service"))
            t <- zioVal.onExecutor(Executor.fromJavaExecutor(executorService))
          } yield t)
          .getOrThrowFiberFailure()
      }
    }
    def runUnsafeOnCurThread(using runtime: Runtime[R]): T = {
      zioVal.runUnsafe(using MoreExecutors.sameThread)
    }
  }

  extension [T](hub: Hub[T]) {
    def onUpdate(consumer: T => Unit)(using ExecutorService)(using r: Runtime[Any])
      : Fiber.Runtime[Throwable, Nothing] = {
      ZIO
        .scoped {
          for {
            queue <- hub.subscribe
            value <- queue.take
            _ <- ZIO.succeed {
              consumer(value)
            }
          } yield ()
        }
        .forever
        .forkDaemon
        .runUnsafe
    }
  }
}
