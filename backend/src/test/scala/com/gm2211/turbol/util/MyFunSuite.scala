package com.gm2211.turbol.util

import org.scalatest.funsuite.AnyFunSuite

abstract class MyFunSuite extends AnyFunSuite {
  def t(name: String)(body: => Unit): Unit = test(name) {
      body
  }
}
