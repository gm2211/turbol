package com.gm2211.turbol.util

object ExpressionUtils extends ExpressionUtils // Allows .* imports

trait ExpressionUtils {
  
  extension [T](value: T) {
    def ignoreRetValue(): Unit = ()
  }
  
  def ignoringRetValue[T](value: => T): Unit = {
    value.asInstanceOf[Unit]
  }
}
