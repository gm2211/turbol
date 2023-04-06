package com.gm2211.reactive

import com.gm2211.reactive.Types.*

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait Task[T] {
  def runAsync(using scheduler: Scheduler): Future[T]
  def runSync(using scheduler: Scheduler): T
  def runOnCurThread: T
}
class TaskImpl[T](lazyValue: => T) extends Task[T] with FutureUtils {
  def runAsync(using scheduler: Scheduler): Future[T] = Future { lazyValue }(using scheduler.toExecutionContext)
  def runSync(using scheduler: Scheduler): T = Future { lazyValue }(using scheduler.toExecutionContext).await()
  def runOnCurThread: T = lazyValue
}
class FailureTask[T](exception: Throwable) extends Task[T] with FutureUtils {
  def runAsync(using scheduler: Scheduler): Future[T] = Future.failed(exception)
  def runSync(using scheduler: Scheduler): T = Future { throw exception }(using scheduler.toExecutionContext).await()
  def runOnCurThread: T = throw exception
}
object Task {
  extension [T](lazyValue: => T) {
    def toTask: Task[T] = new TaskImpl(lazyValue)
  }
  extension [T](tryT: Try[T]) {
    def toTask: Task[T] = tryT match {
      case Success(value) => new TaskImpl(value)
      case Failure(exception) => new FailureTask(exception)
    }
  }
}
