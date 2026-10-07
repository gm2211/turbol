/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import io.circe.Encoder

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference

/** Where a sampled EDR value came from. */
enum EdrSource {
  case Nowcast, Forecast, Blend, NoData
}

object EdrSource {
  given Encoder[EdrSource] = Encoder.encodeString.contramap(_.toString)
}

final case class EdrSample(edr: Double, source: EdrSource)

final case class TurbulenceData(nowcast: Option[EdrFrame], forecast: Vector[EdrFrame]) {
  def forecastIssued: Option[Instant] = forecast.headOption.map(_.issued)
}

/**
 * Holds the latest GTGN nowcast and GTG forecast frames and answers "how rough is it at this place, altitude and
 * time". The nowcast folds in live observations, so it wins near its valid time; its weight tapers linearly to zero
 * over [[TurbulenceStore.nowcastBlendHours]] and the forecast takes over (same scheme as data-pipeline/route_forecast.py).
 */
final class TurbulenceStore {
  private val ref = new AtomicReference(TurbulenceData(None, Vector.empty))

  def current: TurbulenceData = ref.get()
  def setNowcast(frame: EdrFrame): Unit = ref.updateAndGet(_.copy(nowcast = Some(frame))): Unit
  def setForecast(frames: Vector[EdrFrame]): Unit =
    ref.updateAndGet(_.copy(forecast = frames.sortBy(_.validTime))): Unit

  def sample(lat: Double, lon: Double, altitudeFt: Double, time: Instant): EdrSample =
    sampleAt(current, LambertGrid.nearestIndex(lat, lon), altitudeFt, time)

  def sampleAt(data: TurbulenceData, flatIndex: Int, altitudeFt: Double, time: Instant): EdrSample = {
    if (flatIndex < 0) return EdrSample(Double.NaN, EdrSource.NoData)
    val nowcastWeight = data.nowcast.fold(0.0)(n => TurbulenceStore.nowcastWeight(n.validTime, time))
    val nowcastEdr =
      if (nowcastWeight > 0) data.nowcast.fold(Double.NaN)(_.edrAtIndex(flatIndex, altitudeFt)) else Double.NaN
    val forecastEdr = TurbulenceStore.forecastAt(data.forecast, flatIndex, altitudeFt, time)
    if (!nowcastEdr.isNaN && !forecastEdr.isNaN) {
      if (nowcastWeight >= 1.0) EdrSample(nowcastEdr, EdrSource.Nowcast)
      else EdrSample(nowcastWeight * nowcastEdr + (1 - nowcastWeight) * forecastEdr, EdrSource.Blend)
    } else if (!nowcastEdr.isNaN) EdrSample(nowcastEdr, EdrSource.Nowcast)
    else if (!forecastEdr.isNaN) EdrSample(forecastEdr, EdrSource.Forecast)
    else EdrSample(Double.NaN, EdrSource.NoData)
  }
}

object TurbulenceStore {
  val nowcastBlendHours: Double = 1.0
  private val nowcastLookbackHours = 2.0

  /** 1 at (and up to 2 h before) the nowcast's valid time, tapering to 0 one hour after it. */
  def nowcastWeight(nowcastValid: Instant, time: Instant): Double = {
    val hours = Duration.between(nowcastValid, time).toSeconds / 3600.0
    if (hours < -nowcastLookbackHours) 0.0
    else if (hours <= 0) 1.0
    else math.max(0.0, 1.0 - hours / nowcastBlendHours)
  }

  /** Forecast EDR linearly interpolated in time between the two bracketing forecast hours. */
  def forecastAt(frames: Vector[EdrFrame], flatIndex: Int, altitudeFt: Double, time: Instant): Double = {
    if (frames.isEmpty) return Double.NaN
    val first = frames.head
    val last = frames.last
    if (time.isAfter(last.validTime)) Double.NaN
    else if (!time.isAfter(first.validTime)) {
      // Before the first forecast hour (F001): fine within the hour since the cycle started.
      if (Duration.between(time, first.validTime).toMinutes <= 90) first.edrAtIndex(flatIndex, altitudeFt)
      else Double.NaN
    } else {
      val hi = frames.indexWhere(f => !f.validTime.isBefore(time))
      val after = frames(hi)
      val before = frames(hi - 1)
      val span = Duration.between(before.validTime, after.validTime).toSeconds.toDouble
      val w = Duration.between(before.validTime, time).toSeconds / span
      val a = before.edrAtIndex(flatIndex, altitudeFt)
      val b = after.edrAtIndex(flatIndex, altitudeFt)
      if (a.isNaN) b else if (b.isNaN) a else a + w * (b - a)
    }
  }
}
