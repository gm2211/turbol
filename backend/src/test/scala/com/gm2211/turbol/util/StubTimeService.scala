/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.turbol.objects.internal.DatetimeUtc
import com.gm2211.turbol.services.TimeService

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

final class StubTimeService extends TimeService {
  private val epochMillis: AtomicLong = AtomicLong(0)

  override def now: DatetimeUtc = DatetimeUtc(epochMillis.get())
  override def today: LocalDate = LocalDate.ofEpochDay(epochMillis.get().millis.toDays)

  def set(time: DatetimeUtc): Unit = epochMillis.set(time.epochMillis)
  def forward(duration: Duration): Unit = epochMillis.updateAndGet(cur => cur + duration.toMillis)
  def backward(duration: Duration): Unit = forward(duration * -1)
}

object StubTimeService {
  def apply(): StubTimeService = new StubTimeService
}
