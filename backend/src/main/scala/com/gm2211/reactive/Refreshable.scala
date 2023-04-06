package com.gm2211.reactive

import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.Types.Scheduler
import com.gm2211.turbol.backend.util.TryUtils

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.util.Try
import scala.util.control.NonLocalReturns.*

trait Refreshable[T] {
  def update(t: T): Unit
  def onUpdate(f: T => Unit)(using scheduler: Scheduler): Unit
  def get: T
}

class RefreshableImpl[T](initial: T) extends Refreshable[T] with TryUtils with BackendLogging {
  private val value: AtomicReference[T] = new AtomicReference[T](initial)
  private var listeners = mutable.ArrayDeque.empty[(Scheduler, T => Unit)]
  def update(newValue: T): Unit = returning {
    val computedValue = this.value.getAndUpdate(existing => if existing == newValue then existing else newValue)
    if (computedValue == newValue) {
      log.info("Value not changed, not updating listeners")
      throwReturn(())
    }
    value.set(newValue)
    listeners.foreach { (scheduler, listener) =>
      Try {
        scheduler.execute(() => listener(newValue))
      }.ifFailure { error => log.info("Error while scheduling listener update for value change", error) }
    }
  }
  def onUpdate(listener: T => Unit)(using scheduler: Scheduler): Unit = {
    listeners.append((scheduler, listener))
    scheduler.execute(() => listener(value.get()))
  }
  def get: T = value.get()
}

object Refreshable {
  def apply[T](initial: => T): Refreshable[T] = new RefreshableImpl[T](initial)
}
