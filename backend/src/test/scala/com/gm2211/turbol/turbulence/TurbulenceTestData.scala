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
  def uniformFrame(kind: FrameKind, valid: Instant, edrByLevel: Map[Int, Double]): EdrFrame = {
    val points = if (kind == FrameKind.Global) GlobalGrid.nx * GlobalGrid.ny else LambertGrid.nx * LambertGrid.ny
    EdrFrame(
      kind,
      valid,
      valid,
      SortedMap.from(edrByLevel.map { case (level, edr) =>
        val bytes = Array.fill(points)(EdrLayer.encode(edr.toFloat))
        level -> EdrLayer(level, ByteBuffer.wrap(bytes))
      })
    )
  }
}
