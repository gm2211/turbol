package com.gm2211.reactive

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.util.TryUtils

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonLocalReturns.*

trait Refreshable[T] {
  def update(t: T): Unit
  def onUpdate(f: T => Unit)(using executor: ExecutionContext): Unit
  def get: T
  def map[U](mapper: T => U)(using executor: ExecutionContext): Refreshable[U]
  def zipWith[U](other: Refreshable[U])(using executor: ExecutionContext): Refreshable[(T, U)]
}

final class RefreshableImpl[T](initial: T) extends Refreshable[T] with TryUtils with BackendLogging {
  private val value: AtomicReference[T] = new AtomicReference[T](initial)
  private val listeners = mutable.ArrayDeque.empty[(ExecutionContext, T => Unit)]

  override def update(newValue: T): Unit = {
    returning {
      val computedValue = this.value.getAndUpdate(existing => if existing == newValue then existing else newValue)
      if (computedValue == newValue) {
        log.info("Value not changed, not updating listeners")
        throwReturn(())
      }
      value.set(newValue)
      listeners.foreach { (executor, listener) =>
        Try {
          executor.execute(() => listener(newValue))
        }.ifFailure { error => log.info("Error while scheduling listener update for value change", error) }
      }
    }
  }

  override def onUpdate(listener: T => Unit)(using executor: ExecutionContext): Unit = {
    listeners.append((executor, listener))
    executor.execute(() => listener(value.get()))
  }

  override def get: T = value.get()

  override def map[U](mapper: T => U)(using executor: ExecutionContext): Refreshable[U] = {
    val derived = Refreshable(mapper(value.get()))

    onUpdate { newValue =>
      val updated = mapper(newValue)
      if (updated != derived.get) {
        derived.update(updated)
      }
    }

    derived
  }
  
  override def zipWith[U](other: Refreshable[U])(using executor: ExecutionContext): Refreshable[(T, U)] = {
    val derived = Refreshable((value.get, other.get))

    onUpdate { newValue =>
      val updated = (newValue, other.get)
      if (updated != derived.get) {
        derived.update(updated)
      }
    }

    other.onUpdate { newValue =>
      val updated = (value.get, newValue)
      if (updated != derived.get) {
        derived.update(updated)
      }
    }

    derived
  }
}

object Refreshable {
  def apply[T](initial: => T): Refreshable[T] = {
    new RefreshableImpl[T](initial)
  }
}
