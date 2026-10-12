/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.headers.{`Cache-Control`, `Content-Type`}
import org.http4s.server.middleware.GZip
import org.http4s.{CacheDirective, HttpRoutes, MediaType}

import java.time.Instant
import scala.concurrent.duration.*

final case class FrameInfo(id: String, kind: String, validTime: Instant, issued: Instant, levelsFt: Seq[Int])
/** The coarse layer used outside GTG: where it comes from, how coarse it is, and the valid times loaded. */
final case class GlobalLayerInfo(
  source: String,
  method: String,
  resolutionKm: Double,
  issued: Instant,
  validTimes: Seq[Instant],
  /** WAFS cycle blended in from FL140 up, when one was available. */
  wafsIssued: Option[Instant] = None
)

/**
 * @param coverage
 *   GTG's bounding box (the 3 km layer)
 * @param gtgOutline
 *   GTG's exact edge as [lat, lon] pairs; beyond it the map shows the global layer
 */
final case class TurbulenceStatus(
  nowcast: Option[FrameInfo],
  forecast: Seq[FrameInfo],
  thresholds: Map[String, Thresholds],
  coverage: Map[String, Double],
  gtgOutline: Seq[Seq[Double]],
  global: Option[GlobalLayerInfo]
)
final case class PointTurbulence(
  lat: Double,
  lon: Double,
  altitudeFt: Double,
  time: Instant,
  edr: Option[Double],
  category: TurbulenceCategory,
  source: EdrSource
)
final case class ColumnLevel(levelFt: Int, edr: Option[Double], category: TurbulenceCategory)
final case class TurbulenceColumn(
  frameId: String,
  lat: Double,
  lon: Double,
  levels: Seq[ColumnLevel],
  source: EdrSource
)

object TurbulenceEndpoint {

  /**
   * A frame's public id names its GTG frame and the GFS cycle drawn under it, so tiles (cached for a week) change when
   * either does.
   */
  def frameId(frame: EdrFrame, data: TurbulenceData): String =
    data.globalNear(frame.validTime).fold(frame.id)(g => s"${frame.id}_g${g.issued.getEpochSecond}")

  private val gtgOutline: Seq[Seq[Double]] =
    LambertGrid.outline().map { case (lat, lon) => Seq(math.rint(lat * 100) / 100, math.rint(lon * 100) / 100) }

  /** Tile URLs may name this instead of a frame id, so the map can request tiles before it has fetched the status. */
  val latestNowcast = "latest"

  def frameInfo(frame: EdrFrame, data: TurbulenceData): FrameInfo =
    FrameInfo(frameId(frame, data), frame.kind.toString.toLowerCase, frame.validTime, frame.issued, frame.levelsFt)

  def globalInfo(data: TurbulenceData): Option[GlobalLayerInfo] =
    data.globalIssued.map { issued =>
      val wafs = data.globalWafsIssued
      GlobalLayerInfo(
        source = if (wafs.isDefined) "WAFS (AWC) + NOAA GFS" else "NOAA GFS 0.25°",
        method =
          (if (wafs.isDefined) "WAFS EDR (80%) blended with " else "") +
            "Ellrod TI1 index from GFS winds, mapped to EDR by matching GTG over the US",
        resolutionKm = GlobalGrid.resolutionKm,
        issued = issued,
        validTimes = data.global.map(_.validTime),
        wafsIssued = wafs
      )
    }
}

/**
 * GTGN/GTG data for the map, with the coarse global layer outside GTG: what's loaded, map tiles per frame and level,
 * and point queries.
 */
final class TurbulenceEndpoint(store: TurbulenceStore, tiles: TileRenderer, volumes: VolumeSampler)
    extends Endpoint with BackendSerialization {
  import TurbulenceEndpoint.*

  private object LatParam extends QueryParamDecoderMatcher[Double]("lat")
  private object LonParam extends QueryParamDecoderMatcher[Double]("lon")
  private object AltParam extends QueryParamDecoderMatcher[Double]("alt")
  private object TimeParam extends OptionalQueryParamDecoderMatcher[Long]("time")
  private object StepParam extends OptionalQueryParamDecoderMatcher[Int]("step")
  private object TileY {
    def unapply(s: String): Option[Int] = s.stripSuffix(".png").toIntOption
  }

  override val basePath: String = "/turbulence"
  // A stale global suffix (the GFS cycle moved on since the client fetched the status) still finds its GTG frame.
  private def frame(id: String): Option[EdrFrame] = {
    val data = store.current
    if (id == latestNowcast) data.nowcast
    else (data.nowcast.toSeq ++ data.forecast).find(f => f.id == id.takeWhile(_ != '_'))
  }

  // The 3D volume is a few MB of JSON that compresses ~3x; PNG tiles are already compressed.
  override val routes: HttpRoutes[IO] =
    GZip(
      HttpRoutes.of[IO] {
        case GET -> Root / "status" =>
          val data = store.current
          val (south, west, north, east) = LambertGrid.boundingBox
          val status = TurbulenceStatus(
            data.nowcast.map(frameInfo(_, data)),
            data.forecast.map(frameInfo(_, data)),
            Thresholds.byClass.map { case (k, v) => k.toString -> v },
            Map("south" -> south, "west" -> west, "north" -> north, "east" -> east),
            gtgOutline,
            globalInfo(data)
          )
          Ok(status.toJson.deepDropNullValues)

        case GET -> Root / "tiles" / id / IntVar(level) / IntVar(z) / IntVar(x) / TileY(y) =>
          frame(id) match {
            case Some(f) if z >= 0 && z <= 12 =>
              val global = store.current.globalNear(f.validTime)
              // A frame id names immutable data; "latest" moves every 15 minutes.
              val maxAge = if (id == latestNowcast) 1.minute else 7.days
              IO.blocking(tiles.render(f, global, level, z, x, y)).flatMap { png =>
                Ok(png).map(
                  _.withContentType(`Content-Type`(MediaType.image.png))
                    .putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(maxAge)))
                )
              }
            case _ => NotFound("frame or level not loaded")
          }

        case GET -> Root / "point" :? LatParam(lat) +& LonParam(lon) +& AltParam(alt) +& TimeParam(time) =>
          val at = time.map(Instant.ofEpochSecond).getOrElse(Instant.now())
          val sample = store.sample(lat, lon, alt, at)
          val point = PointTurbulence(
            lat,
            lon,
            alt,
            at,
            Option(sample.edr).filterNot(_.isNaN),
            Thresholds.classify(sample.edr),
            sample.source
          )
          Ok(point.toJson.deepDropNullValues)

        case GET -> Root / "volume" / id :? StepParam(step) =>
          frame(id) match {
            case Some(f) =>
              val s = step.getOrElse(6).max(VolumeSampler.minStep).min(24)
              val global = store.current.globalNear(f.validTime)
              IO.blocking(volumes.volume(f, global, s)).flatMap { v =>
                Ok(v.toJson).map(_.putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(1.hour))))
              }
            case None => NotFound("frame not loaded")
          }

        case GET -> Root / "column" / id :? LatParam(lat) +& LonParam(lon) =>
          frame(id) match {
            case Some(f) =>
              // Outside GTG the column comes from the global layer valid closest to the frame.
              val gtgIndex = LambertGrid.nearestIndex(lat, lon)
              val column =
                if (gtgIndex >= 0) Some(f -> gtgIndex)
                else store.current.globalNear(f.validTime).map(g => g -> g.nearestIndex(lat, lon))
              val levels = column.toSeq.flatMap { case (source, index) =>
                source.levels.toSeq.map { case (ft, layer) =>
                  val edr = layer.edrAtIndex(index)
                  ColumnLevel(ft, Option(edr).filterNot(_.isNaN), Thresholds.classify(edr))
                }
              }
              val source = column.fold(EdrSource.NoData)(c =>
                if (c._1.kind == FrameKind.Global) EdrSource.Global
                else if (f.kind == FrameKind.Nowcast) EdrSource.Nowcast
                else EdrSource.Forecast
              )
              Ok(TurbulenceColumn(id, lat, lon, levels, source).toJson.deepDropNullValues)
            case None => NotFound("frame not loaded")
          }
      },
      isZippable = _.contentType.exists(_.mediaType == MediaType.application.json)
    )
}
