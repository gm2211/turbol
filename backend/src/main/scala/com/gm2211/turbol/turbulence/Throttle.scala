/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.Visitors

/**
 * Caps a background computation's share of wall-clock time: call [[tick]] often (every few ms of work), and once a
 * slice of work has run it sleeps long enough to keep the busy fraction at `duty`. The instance has a fraction of a
 * CPU, so a decode that runs flat out starves the request threads; sleeping hands the CPU back to them. While a
 * visitor is being served it pauses outright (see [[Visitors]]).
 *
 * Not thread-safe: one per computation.
 */
final class Throttle(duty: Double, sliceMillis: Long = 40) {
  require(duty > 0 && duty <= 1, s"duty must be in (0, 1], was $duty")
  private val sliceNanos = sliceMillis * 1000000L
  private var sliceStart = System.nanoTime()

  def tick(): Unit =
    if (duty < 1) {
      val busy = System.nanoTime() - sliceStart
      if (busy >= sliceNanos) {
        Thread.sleep(math.max(1L, (busy * (1 - duty) / duty / 1000000L).toLong))
        Visitors.waitForQuiet()
        sliceStart = System.nanoTime()
      }
    }
}

object Throttle {

  /** No throttling: for tests and offline tools. */
  def off: Throttle = new Throttle(1.0)
}
