/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object TryUtils extends TryUtils // Allows ._ imports

trait TryUtils {
  extension (boolean: Boolean) {
    def toTry[T](ifTrue: () => T, ifFalse: => Throwable): Try[T] = {
      if boolean then {
        Success(ifTrue())
      } else {
        Failure(ifFalse)
      }
    }
  }
  extension [T](tr: Try[T]) {
    def ifSuccess(action: T => Unit): Try[T] = {
      if tr.isSuccess then {
        action(tr.get)
      }
      tr
    }

    def ifFailure(action: Throwable => Unit): Try[T] = {
      tr.recoverWith { case error: Throwable =>
        action(error)
        Failure(error)
      }
    }

    def toFuture: Future[T] =
      tr match {
        case Failure(exception) => Future.failed(exception)
        case Success(value) => Future.successful(value)
      }
  }
}
