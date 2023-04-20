package com.gm2211.turbol.util

import scala.concurrent.ExecutionContext

/**
 * Mostly exists to get around macwire's limitation to tag ExecutorService or ExecutionContext
 * (probably related to some packaging issue related to newer jdks and their security).
 */
trait Scheduler { def executionContext: ExecutionContext }
class SchedulerImpl(val executionContext: ExecutionContext) extends Scheduler {}
