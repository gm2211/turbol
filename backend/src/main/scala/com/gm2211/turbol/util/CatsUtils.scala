package com.gm2211.turbol.util

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.gm2211.turbol.util.MoreExecutors.*

import java.util.concurrent.ExecutorService
import scala.concurrent.ExecutionContext

object CatsUtils extends CatsUtils { // Allows us to import CatsUtils._ in other files
  val runtime: IORuntime = IORuntime.global // Not used for blocking operations, because we override it
  val defaultIoExecutor: ExecutionContext = MoreExecutors.io("main-io-pool").executionContext
}

trait CatsUtils {
  import CatsUtils.*
  given IORuntime = runtime

  extension [T](io: IO[T]) {
    def runIO(): T = io.evalOnIOExec().unsafeRunSync()
    def runCompute(): T = io.unsafeRunSync()
    def evalOnIOExec(): IO[T] = io.evalOn(defaultIoExecutor)
  }
}
