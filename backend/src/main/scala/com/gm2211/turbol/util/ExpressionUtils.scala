/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

object ExpressionUtils extends ExpressionUtils // Allows .* imports

trait ExpressionUtils {

  given listConversion[T]: Conversion[T, Seq[T]] = Seq(_)
  
  extension [T](value: T) {
    def ignoreRetValue(): Unit = ()
  }
  
  def ignoringRetValue[T](value: => T): Unit = {
    value.asInstanceOf[Unit]
  }
}
