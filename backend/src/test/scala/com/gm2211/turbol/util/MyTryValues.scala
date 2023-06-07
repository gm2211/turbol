/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import org.scalactic.source
import org.scalatest.exceptions.{StackDepthException, TestFailedException}

import scala.util.{Failure, Success, Try}

/** Alternative to org.scalatest.TryValues with better error messages. */
trait MyTryValues {
  import ExpressionUtils.*
  extension [T](theTry: Try[T]) {
    def failure(implicit pos: source.Position): Failure[T] = {
      theTry match {
        case failure: Failure[T] => failure
        case Success(value) =>
          throw new TestFailedException((_: StackDepthException) => Some(s"Expected failure but got $value"), None, pos)
      }
    }

    def value(implicit pos: source.Position): T = success.value
    
    def assertSuccess(implicit pos: source.Position): Unit = ignoringRetValue {
      success
    }
    
    def success(implicit pos: source.Position): Success[T] = {
      theTry match {
        case success: Success[T] => success
        case Failure(exception) =>
          throw new TestFailedException(
            (_: StackDepthException) => Some(s"Expected success but got $exception"),
            None,
            pos
          )
      }
    }

  }
}
