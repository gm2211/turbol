/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import java.nio.ByteBuffer
import java.time.Instant
import scala.collection.immutable.SortedMap

object TurbulenceTestData {
  /** A frame where every grid point has the same EDR on every level. */
  def uniformFrame(kind: FrameKind, valid: Instant, edrByLevel: Map[Int, Double]): EdrFrame =
    EdrFrame(
      kind,
      valid,
      valid,
      SortedMap.from(edrByLevel.map { case (level, edr) =>
        val bytes = Array.fill(LambertGrid.nx * LambertGrid.ny)(EdrLayer.encode(edr.toFloat))
        level -> EdrLayer(level, ByteBuffer.wrap(bytes))
      })
    )
}
