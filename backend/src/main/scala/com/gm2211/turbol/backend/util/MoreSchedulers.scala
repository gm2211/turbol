/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import com.gm2211.turbol.backend.logging.{BackendLogger, BackendLogging}

import java.util.concurrent.*
import scala.concurrent.duration.{Duration, FiniteDuration}

object MoreSchedulers extends BackendLogging {

  /** Builds a SchedulerService backed by an internal
    * `java.util.concurrent.ThreadPoolExecutor`, that executes each submitted
    * task using one of possibly several pooled threads.
    *
    * The default implementations either does not scale neither up nor down
    * (fixedThreadPool) or it reject tasks if it cannot scale past maximum
    * (because of the way ThreadPool is implemented: it only creates a new
    * thread if the number of threads < core size or the provided queue rejects
    * a task)
    *
    * This one instead has the best of both worlds:
    *   1. Reclaims unused threads 2. Creates new threads up to maximum when
    *      necessary 3. Queue tasks when maximum is reached and no available
    *      thread
    */
  def boundedCached(
      name: String,
      maxThreads: Int,
      keepAliveTime: FiniteDuration = FiniteDuration(60, TimeUnit.SECONDS)
  ): ExecutorService = {

    require(maxThreads > 0, "maxThreads > 0")
    require(keepAliveTime >= Duration.Zero, "keepAliveTime >= 0")

    val threadFactory =
      ThreadFactoryBuilder(
        name,
        e => log.info("Unhandled exception", e),
        daemonic = true
      )
    val executor = new ThreadPoolExecutor(
      maxThreads, // core size, but we allow core threads to expire
      maxThreads, // max size
      keepAliveTime.toMillis,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue[Runnable](),
      threadFactory
    )

    // Allow core threads to timeout so we can reclaim resources
    executor.allowCoreThreadTimeOut(true)

    executor
  }

  /** A scheduler that executes tasks on the same thread that scheduled them. */
  def sameThread(): Executor = (command: Runnable) => command.run()

  def threadFactory(threadNamePattern: String): ThreadFactory = {
    ThreadFactoryBuilder(
      threadNamePattern,
      e => log.info("Unhandled exception", e),
      daemonic = true
    )
  }

  private object ThreadFactoryBuilder {

    /** Constructs a ThreadFactory using the provided name prefix and appending
      * with a unique incrementing thread identifier.
      *
      * @param name
      *   the created threads name prefix, for easy identification.
      * @param daemonic
      *   specifies whether the created threads should be daemonic (non-daemonic
      *   threads are blocking the JVM process on exit).
      */
    def apply(
        name: String,
        reporter: Throwable => Unit,
        daemonic: Boolean
    ): ThreadFactory = { (r: Runnable) =>
      {
        val thread = new Thread(r)
        thread.setName(s"$name-${thread.getId}")
        thread.setDaemon(daemonic)
        thread.setUncaughtExceptionHandler((_: Thread, e: Throwable) =>
          reporter(e)
        )

        thread
      }
    }
  }
}
