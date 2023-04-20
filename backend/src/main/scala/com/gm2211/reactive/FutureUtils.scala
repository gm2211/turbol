/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.reactive

import java.util.concurrent.ExecutorService
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import com.gm2211.turbol.util.MoreExecutors.*

object FutureUtils extends FutureUtils // Allows ._ imports
trait FutureUtils {
  extension [T](future: Future[T]) {
    def await(): T = Await.result(future, Duration.Inf)
    def await(duration: FiniteDuration): T = Await.result(future, duration)
    def handleSuccess(valueHandler: T => Unit)(executorService: ExecutorService): Unit =
      future.foreach(valueHandler)(executorService.toExecutionContext)
    def handleFailure(throwableHandler: Throwable => Unit)(executorService: ExecutorService): Unit = {
      future.failed.foreach(throwableHandler)(executorService.toExecutionContext)
    }
  }
}
