package com.gm2211.reactive

import java.util.concurrent.ExecutorService
import scala.Conversion
import scala.concurrent.ExecutionContext

object Types {
  type Scheduler = ExecutorService
  extension (s: Scheduler) {
    def toExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(s)
  }
}
