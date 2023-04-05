/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.services

import com.gm2211.turbol.backend.objects.internal.DatetimeUtc

import java.time.{Clock, LocalDate}
import scala.concurrent.duration.*

trait TimeService {

  /** The current time. */
  def now: DatetimeUtc

  /** The current day. */
  def today: LocalDate

  /** The amount of time between now and the provided time. Can be negative if 'until' is in the past. */
  def timeUntil(until: DatetimeUtc): Duration = (until.epochMillis - now.epochMillis).millis

  /** The amount of time between the provided time and now. Can be negatice if 'since' is in the future. */
  def timeSince(since: DatetimeUtc): Duration = (now.epochMillis - since.epochMillis).millis
}

final class SystemTimeService extends TimeService {
  private val utcClock: Clock = Clock.systemUTC()

  override def now: DatetimeUtc = DatetimeUtc(utcClock.millis())
  override def today: LocalDate = LocalDate.now(utcClock)
}
