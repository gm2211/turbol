/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.reactive

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions

object FutureUtils extends FutureUtils // Allows ._ imports
trait FutureUtils {
  import com.gm2211.reactive.Types.*
  extension [T](future: Future[T]) {
    def await(): T = Await.result(future, Duration.Inf)
    def await(duration: FiniteDuration): T = Await.result(future, duration)
    def handleSuccess(valueHandler: T => Unit)(scheduler: Scheduler): Unit =
      future.foreach(valueHandler)(scheduler.toExecutionContext)
    def handleFailure(throwableHandler: Throwable => Unit)(scheduler: Scheduler): Unit = {
      future.failed.foreach(throwableHandler)(scheduler.toExecutionContext)
    }
  }
}
