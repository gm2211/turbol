package com.gm2211.turbol.backend.logging

sealed trait Arg[T] {
  val name: String
  val value: T
}

case class SafeArg[T](name: String, value: T) extends Arg[T] {
  override def toString: String = s"safe($name, $value)"
}

case class UnsafeArg[T](name: String, value: T) extends Arg[T] {
  override def toString: String = s"unsafe($name, $value)"
}
