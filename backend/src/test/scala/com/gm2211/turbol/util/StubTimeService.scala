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

final class StubTimeService extends TimeService with ExpressionUtils {
  private val epochMillis: AtomicLong = AtomicLong(0)

  override def now: DatetimeUtc = DatetimeUtc(epochMillis.get())
  override def today: LocalDate = LocalDate.ofEpochDay(epochMillis.get().millis.toDays)

  def set(time: DatetimeUtc): StubTimeService = {
    epochMillis.set(time.epochMillis)
    this
  }
  def forward(duration: Duration): StubTimeService = {
    epochMillis.updateAndGet(cur => cur + duration.toMillis)
    this
  }
  def backward(duration: Duration): StubTimeService = {
    forward(duration * -1)
    this
  }
}

object StubTimeService {
  def apply(): StubTimeService = new StubTimeService
}
