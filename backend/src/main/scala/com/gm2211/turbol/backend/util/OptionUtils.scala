/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object OptionUtils extends OptionUtils // Allows ._ imports

trait OptionUtils {
  def booleanToOption(condition: Boolean): Option[Unit] = {
    if (condition) {
      Some(())
    } else {
      None
    }
  }

  extension [T](value: T) {
    def asOption: Option[T] = Option(value)
  }

  implicit class OptionExtensions[T](option: Option[T]) {
    def toFuture: Future[T] =
      option match {
        case Some(value) => Future.successful(value)
        case None => Future.failed(new IllegalArgumentException("No value"))
      }

    def toTry(fieldName: String): Try[T] = toTry(new IllegalStateException(s"Optional value is missing for $fieldName"))

    def toTry: Try[T] = toTry(new IllegalStateException("Optional value is missing"))

    def toTry(exception: => Exception): Try[T] =
      option
        .map(Success(_))
        .getOrElse(Failure(exception))

    def peek(consumer: T => Unit): Option[T] = {
      option.foreach(consumer)
      option
    }

    def ifEmpty(action: () => Unit): Option[T] = {
      action()
      option
    }

    def ifPresent(consumer: T => Unit): OrElse.type = {
      option.foreach(consumer)
      OrElse
    }

    /**
     * Same as Option#get but will elude wartremover.
     * Should only be used when we know for a fact the Option is present.
     */
    def orThrow(): T = orThrow(new IllegalStateException("Expecting value to be present"))

    def orThrow(exception: => Exception): T = {
      option match {
        case Some(value) => value
        case None => throw exception
      }
    }

    object OrElse {
      def orElse(action: () => Unit): Unit = if (option.isEmpty) action()
    }
  }
}
