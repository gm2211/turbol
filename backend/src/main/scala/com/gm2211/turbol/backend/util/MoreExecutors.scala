/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.util

import com.gm2211.turbol.backend.logging.{BackendLogger, BackendLogging}

import java.util
import java.util.concurrent.*
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.Try

object MoreExecutors extends BackendLogging {

  extension (executorService: ExecutorService) {
    def toExecutionContext: ExecutionContext = ExecutionContext.fromExecutor(executorService)
  }

  given Conversion[ExecutorService, ExecutionContext] = _.toExecutionContext

  def sameThread: ExecutorService = new ExecutorService {
    override def shutdown(): Unit = ()
    override def shutdownNow(): util.List[Runnable] = List().asJava
    override def isShutdown: Boolean = false
    override def isTerminated: Boolean = false
    override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = false
    override def submit[T](task: Callable[T]): Future[T] = {
      Try { task.call }.fold(CompletableFuture.failedFuture, CompletableFuture.completedFuture)
    }
    override def submit[T](task: Runnable, result: T): Future[T] = {
      task.run()
      CompletableFuture.completedFuture(result)
    }
    override def submit(task: Runnable): Future[_] = {
      task.run()
      CompletableFuture.completedFuture(())
    }
    override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]]): util.List[Future[T]] =
      tasks.asScala.map(task => submit(task)).toList.asJava
    override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit)
      : util.List[Future[T]] = invokeAll(tasks) // TODO: implement timeout
    override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]]): T = tasks.asScala.head.call()
    override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): T =
      tasks.asScala.head.call() // TODO: implement timeout
    override def execute(command: Runnable): Unit = command.run()
  }

  /** Builds a SchedulerService backed by an internal `java.util.concurrent.ThreadPoolExecutor`, that executes each
    * submitted task using one of possibly several pooled threads.
    *
    * The default implementations either does not scale neither up nor down (fixedThreadPool) or it reject tasks if it
    * cannot scale past maximum (because of the way ThreadPool is implemented: it only creates a new thread if the
    * number of threads < core size or the provided queue rejects a task)
    *
    * This one instead has the best of both worlds:
    *   1. Reclaims unused threads 2. Creates new threads up to maximum when necessary 3. Queue tasks when maximum is
    *      reached and no available thread
    */
  def boundedCached(
    name: String,
    maxThreads: Int,
    keepAliveTime: FiniteDuration = FiniteDuration(60, TimeUnit.SECONDS)
  ): ExecutorService = {

    require(maxThreads > 0, "maxThreads > 0")
    require(keepAliveTime >= Duration.Zero, "keepAliveTime >= 0")

    val threadFactory =
      ThreadFactoryBuilder(name, daemonic = true)
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

  private object ThreadFactoryBuilder extends BackendLogging {

    /** Constructs a ThreadFactory using the provided name prefix and appending with a unique incrementing thread
      * identifier.
      */
    def apply(name: String, daemonic: Boolean): ThreadFactory = { (r: Runnable) =>
      val thread = new Thread(r)

      thread.setName(s"$name-${thread.getName}")
      thread.setDaemon(daemonic)
      thread.setUncaughtExceptionHandler((_: Thread, e: Throwable) => log.warn("Unhandled exception", e))

      thread
    }
  }
}
