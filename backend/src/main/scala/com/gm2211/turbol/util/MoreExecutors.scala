/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.logging.BackendLogging

import java.nio.file.{Files, Paths}
import java.util
import java.util.concurrent.*
import scala.concurrent.ExecutionContext
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
    override def submit(task: Runnable): Future[?] = {
      task.run()
      CompletableFuture.completedFuture(())
    }
    override def invokeAll[T](tasks: util.Collection[? <: Callable[T]]): util.List[Future[T]] =
      tasks.asScala.map(task => submit(task)).toList.asJava
    override def invokeAll[T](
      tasks: util.Collection[? <: Callable[T]],
      timeout: Long,
      unit: TimeUnit
    ): util.List[Future[T]] = invokeAll(tasks) // TODO: implement timeout
    override def invokeAny[T](tasks: util.Collection[? <: Callable[T]]): T = tasks.asScala.head.call()
    override def invokeAny[T](tasks: util.Collection[? <: Callable[T]], timeout: Long, unit: TimeUnit): T =
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

  /**
   * Like [[io]], but its threads run at the lowest CPU priority (nice 19). The production instance has a tenth of a
   * CPU: at equal priority, decoding turbulence data left a visitor's page and tiles waiting 10 s or more for a turn,
   * since one GRIB level takes seconds of CPU and can't be paused midway. At nice 19 the request threads get the CPU
   * whenever they want it, and background work gets the rest. `threads` > 0 caps the pool at that many threads.
   */
  def background(namePrefix: String, threads: Int = 0): Scheduler = {
    val threadFactory = ThreadFactoryBuilder(namePrefix, daemonic = true)
    val lowPriority: ThreadFactory = (r: Runnable) =>
      threadFactory.newThread { () =>
        lowerCurrentThreadPriority()
        r.run()
      }
    val pool =
      if (threads > 0) Executors.newFixedThreadPool(threads, lowPriority)
      else Executors.newCachedThreadPool(lowPriority)
    pool.toExecutionContext.toScheduler
  }

  private def lowerCurrentThreadPriority(): Unit =
    Try {
      // Linux gives each thread its own task; renice on its id (from /proc/thread-self -> <pid>/task/<tid>) lowers
      // this thread only. Java's own thread priorities are ignored on Linux.
      val tid = Files.readSymbolicLink(Paths.get("/proc/thread-self")).getFileName.toString
      val renice = new ProcessBuilder("renice", "-n", "19", "-p", tid)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
      require(renice.waitFor() == 0, s"renice exited with ${renice.exitValue()}")
      niceOfCurrentThread
    }.fold(
      e => log.warn("Background thread keeps its normal CPU priority", e),
      nice => log.info("Background thread priority lowered", safe("nice", nice))
    )

  /** The calling thread's nice value: field 19 of /proc/thread-self/stat, counted after the command in parentheses. */
  def niceOfCurrentThread: Int = {
    val stat = Files.readString(Paths.get("/proc/thread-self/stat"))
    stat.substring(stat.lastIndexOf(')') + 2).split(' ')(16).toInt
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
