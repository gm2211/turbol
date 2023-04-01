package com.gm2211.turbol.backend.util

import zio.{Runtime, UIO, Unsafe}

object UIOUtils extends UIOUtils // Allows .* imports
trait UIOUtils {
  extension [T](uio: UIO[T]) {
    def runUnsafe(using runtime: Runtime[Any]): T = {
      Unsafe.unsafely {
        runtime.unsafe.run(uio).getOrThrowFiberFailure()
      }
    }
  }
}
