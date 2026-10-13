/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import io.circe.Encoder

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.math.rint

/** Where a sampled EDR value came from. Global is the coarse GFS-derived layer outside (or past) GTG. */
enum EdrSource {
  case Nowcast, Forecast, Blend, Global, NoData
}

object EdrSource {
  given Encoder[EdrSource] = Encoder.encodeString.contramap(_.toString)
}

final case class EdrSample(edr: Double, source: EdrSource)

/** @param global the coarse GFS-derived layer, every few hours of one GFS cycle, sorted by valid time */
final case class TurbulenceData(
  nowcast: Option[EdrFrame],
  forecast: Vector[EdrFrame],
  global: Vector[EdrFrame] = Vector.empty,
  /** Newest WAFS cycle blended into the global layer, if any (see [[Wafs]]). */
  globalWafsIssued: Option[Instant] = None,
  /**
   * Sources (nowcast, forecast, global) not yet checked since the server started. Until each has been, the data can
   * be a few frames of what is on its way, so Flight Packs wait (see [[TurbulenceDataUpdater]]).
   */
  loading: Set[String] = Set.empty
) {
  def forecastIssued: Option[Instant] = forecast.headOption.map(_.issued)

  /** The frame the live map opens on: the nowcast, else the first forecast hour more than 15 minutes ahead. */
  def opening(now: Instant = Instant.now()): Option[EdrFrame] =
    nowcast.orElse(forecast.find(_.validTime.isAfter(now.plusSeconds(15 * 60))))
  def globalIssued: Option[Instant] = global.headOption.map(_.issued)

  /** The global frame closest in time to `time`, within [[TurbulenceStore.globalSlackHours]]: drawn under a GTG frame. */
  def globalNear(time: Instant): Option[EdrFrame] =
    global
      .minByOption(g => math.abs(Duration.between(g.validTime, time).toSeconds))
      .filter(g => math.abs(Duration.between(g.validTime, time).toSeconds) <= TurbulenceStore.globalSlackHours * 3600)
}

/**
 * Holds the latest GTGN nowcast and GTG forecast frames and answers "how rough is it at this place, altitude and
 * time". The nowcast folds in live observations, so it wins near its valid time; its weight tapers linearly to zero
 * over [[TurbulenceStore.nowcastBlendHours]] and the forecast takes over (same scheme as data-pipeline/route_forecast.py).
 *
 * Outside the GTG grid, or where GTG has no value, the coarse global layer answers instead; across the last
 * [[LambertGrid.blendCells]] cells inside the grid the two are mixed linearly so there is no seam.
 */
final class TurbulenceStore(loading: Set[String] = Set.empty) {
  private val ref = new AtomicReference(TurbulenceData(None, Vector.empty, loading = loading))

  def current: TurbulenceData = ref.get()

  /** A source has been checked once since the server started, whether or not it found data. */
  def loaded(source: String): Unit =
    ref.updateAndGet(d => if (d.loading(source)) d.copy(loading = d.loading - source) else d): Unit

  def setNowcast(frame: EdrFrame): Unit = ref.updateAndGet(_.copy(nowcast = Some(frame))): Unit
  def setForecast(frames: Vector[EdrFrame]): Unit =
    ref.updateAndGet(_.copy(forecast = frames.sortBy(_.validTime))): Unit

  def setGlobal(frames: Vector[EdrFrame], wafsIssued: Option[Instant] = None): Unit =
    ref.updateAndGet(_.copy(global = frames.sortBy(_.validTime), globalWafsIssued = wafsIssued)): Unit

  def sample(lat: Double, lon: Double, altitudeFt: Double, time: Instant): EdrSample =
    sampleIn(current, lat, lon, altitudeFt, time)

  /** GTG where it has data, the global layer elsewhere, blended near GTG's edge. */
  def sampleIn(data: TurbulenceData, lat: Double, lon: Double, altitudeFt: Double, time: Instant): EdrSample = {
    val gtgIndex = LambertGrid.nearestIndex(lat, lon)
    val gtg = sampleAt(data, gtgIndex, altitudeFt, time)
    val weight = if (gtgIndex < 0) 0.0 else LambertGrid.interiorWeightAt(lat, lon)
    if (weight >= 1.0 && !gtg.edr.isNaN) gtg
    else {
      val global = TurbulenceStore.globalAt(data.global, GlobalGrid.nearestIndex(lat, lon), altitudeFt, time)
      if (global.isNaN) gtg
      else if (gtg.edr.isNaN) EdrSample(global, EdrSource.Global)
      else EdrSample(weight * gtg.edr + (1 - weight) * global, if (weight >= 0.5) gtg.source else EdrSource.Global)
    }
  }

  /**
   * [[sampleIn]] at several altitudes of one place and time, projecting the place onto the grids once rather than once
   * per altitude (the projection is most of a sample's cost): the route corridor samples 13 levels per place.
   */
  def sampleColumnIn(data: TurbulenceData, lat: Double, lon: Double, altitudesFt: Seq[Double], time: Instant): Array[Double] = {
    val inGtg = lat >= 0 && lat <= 89
    val (fi, fj) = if (inGtg) LambertGrid.fractionalIndex(lat, lon) else (-1.0, -1.0)
    val (i, j) = (rint(fi).toInt, rint(fj).toInt)
    val gtgIndex = if (!inGtg || i < 0 || i >= LambertGrid.nx || j < 0 || j >= LambertGrid.ny) -1 else j * LambertGrid.nx + i
    val weight = if (gtgIndex < 0) 0.0 else LambertGrid.interiorWeight(fi, fj)
    val globalIndex = GlobalGrid.nearestIndex(lat, lon)
    altitudesFt.iterator.map { alt =>
      val gtg = sampleAt(data, gtgIndex, alt, time)
      if (weight >= 1.0 && !gtg.edr.isNaN) gtg.edr
      else {
        val global = TurbulenceStore.globalAt(data.global, globalIndex, alt, time)
        if (global.isNaN) gtg.edr
        else if (gtg.edr.isNaN) global
        else weight * gtg.edr + (1 - weight) * global
      }
    }.toArray
  }

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

  /** Global frames are 3 hours apart; one is used up to this far from its valid time before the first or after the last. */
  val globalSlackHours: Double = 3.0
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

  /** Global-layer EDR interpolated in time between the two bracketing frames. NaN when no frame is close enough. */
  def globalAt(frames: Vector[EdrFrame], flatIndex: Int, altitudeFt: Double, time: Instant): Double = {
    if (frames.isEmpty || flatIndex < 0) return Double.NaN
    val hi = frames.indexWhere(f => !f.validTime.isBefore(time))
    if (hi == 0 || hi < 0) {
      val nearest = if (hi == 0) frames.head else frames.last
      val gapHours = math.abs(Duration.between(nearest.validTime, time).toSeconds) / 3600.0
      if (gapHours <= globalSlackHours) nearest.edrAtIndex(flatIndex, altitudeFt) else Double.NaN
    } else {
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
