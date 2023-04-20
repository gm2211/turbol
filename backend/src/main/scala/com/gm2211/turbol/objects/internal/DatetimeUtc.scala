/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal

import java.time.Instant
import scala.concurrent.duration.*

final case class DatetimeUtc(epochMillis: Long) {
  def plus(duration: Duration): DatetimeUtc = this + duration
  def +(duration: Duration): DatetimeUtc = DatetimeUtc(epochMillis + duration.toMillis)

  def minus(duration: Duration): DatetimeUtc = this - duration
  def -(duration: Duration): DatetimeUtc = DatetimeUtc(epochMillis - duration.toMillis)

  def until(endTimeExclusive: DatetimeUtc): Duration = (endTimeExclusive.epochMillis - epochMillis).millis
  def since(startTimeInclusive: DatetimeUtc): Duration = startTimeInclusive.until(this)

  def toInstant: Instant = Instant.ofEpochMilli(epochMillis)

  def before(other: DatetimeUtc): Boolean = until(other) > Duration.Zero
  def after(other: DatetimeUtc): Boolean = since(other) > Duration.Zero

  def beforeOrEq(other: DatetimeUtc): Boolean = until(other) >= Duration.Zero
  def afterOrEq(other: DatetimeUtc): Boolean = since(other) >= Duration.Zero
}

object DatetimeUtc {
  given Ordering[DatetimeUtc] = Ordering.by(_.epochMillis)
}
