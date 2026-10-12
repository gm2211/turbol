/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import scala.concurrent.duration.*

/**
 * When someone last asked the server for something, so background work can step aside for them. The production
 * instance has a tenth of a CPU: rendering a new forecast's tiles in the background took long enough to delay a
 * visitor's page by 6 s and their first turbulence tile by 15 s.
 */
object Visitors {
  @volatile private var lastSeenNanos: Long = System.nanoTime() - 1.hour.toNanos
  private val quietFor = 2.seconds

  def seen(): Unit = lastSeenNanos = System.nanoTime()

  def present: Boolean = System.nanoTime() - lastSeenNanos < quietFor.toNanos

  /** Blocks while a visitor is being served, for up to `atMost`, so background work doesn't stall forever. */
  def waitForQuiet(atMost: FiniteDuration = 3.seconds): Unit = {
    val deadline = System.nanoTime() + atMost.toNanos
    while (present && System.nanoTime() < deadline) Thread.sleep(100)
  }
}
