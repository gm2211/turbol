/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class MoreExecutorsTest extends BaseTest {
  private def currentNice(): Int = MoreExecutors.niceOfCurrentThread

  test("background threads run at the lowest CPU priority, and other threads keep theirs") {
    assume(Files.exists(Paths.get("/proc/thread-self")), "Linux only")
    val ec = MoreExecutors.background("test-background").executionContext
    Await.result(Future(currentNice())(using ec), 10.seconds) shouldBe 19
    currentNice() should not be 19
  }
}
