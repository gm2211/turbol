/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.logging.{BackendLogger, BackendLogging}

import java.util
import java.util.concurrent.*
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.Try

object MoreExecutors extends BackendLogging {
  extension (executorService: ExecutorService) {
    def toExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(executorService)
  }
  extension (executionContext: ExecutionContext) {
    def toScheduler: Scheduler = SchedulerImpl(executionContext)
  }

  given Conversion[ExecutorService, ExecutionContext] = _.toExecutionContext
  given Conversion[ExecutionContext, Scheduler] = _.toScheduler
  given Conversion[Scheduler, ExecutionContext] = _.executionContext

  def sameThread: Scheduler = new ExecutorService {
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
    override def invokeAll[T](
      tasks: util.Collection[_ <: Callable[T]],
      timeout: Long,
      unit: TimeUnit
    ): util.List[Future[T]] = invokeAll(tasks) // TODO: implement timeout
    override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]]): T = tasks.asScala.head.call()
    override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): T =
      tasks.asScala.head.call() // TODO: implement timeout
    override def execute(command: Runnable): Unit = command.run()
  }.toExecutionContext.toScheduler

  /**
   * Creates a new executor service that reuses pooled threads if possible, otherwise creates new threads.
   * Should be used for blocking operations.
   */
  def io(namePrefix: String): Scheduler = {
    val threadFactory = ThreadFactoryBuilder(namePrefix, daemonic = true)
    Executors.newCachedThreadPool(threadFactory).toExecutionContext.toScheduler
  }

  /**
   * Creates a threadpool executor with a fixed number of threads = number of cores.
   * Should be used for non-blocking operations.
   */
  def fixed(namePrefix: String, numThreads: Int, daemonic: Boolean = true): Scheduler = {
    val threadFactory = ThreadFactoryBuilder(namePrefix, daemonic)
    Executors.newFixedThreadPool(numThreads, threadFactory).toExecutionContext.toScheduler
  }

  private object ThreadFactoryBuilder extends BackendLogging {

    /**
     * Constructs a ThreadFactory using the provided name prefix and appending with a unique incrementing thread
     * identifier.
     */
    def apply(namePrefix: String, daemonic: Boolean): ThreadFactory = { (r: Runnable) =>
      val thread = new Thread(r)

      thread.setName(s"$namePrefix-${thread.getName.toLowerCase}")
      thread.setDaemon(daemonic)
      thread.setUncaughtExceptionHandler((_: Thread, e: Throwable) => log.warn("Unhandled exception", e))

      thread
    }
  }
}
