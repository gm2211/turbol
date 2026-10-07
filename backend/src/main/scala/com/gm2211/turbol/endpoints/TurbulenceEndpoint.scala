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
final case class TurbulenceStatus(
  nowcast: Option[FrameInfo],
  forecast: Seq[FrameInfo],
  thresholds: Map[String, Thresholds],
  coverage: Map[String, Double]
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
final case class TurbulenceColumn(frameId: String, lat: Double, lon: Double, levels: Seq[ColumnLevel])

object TurbulenceEndpoint {
  def frameId(frame: EdrFrame): String = frame.kind match {
    case FrameKind.Nowcast => s"n${frame.validTime.getEpochSecond}"
    case FrameKind.Forecast => s"f${frame.issued.getEpochSecond}-${frame.validTime.getEpochSecond}"
  }

  def frameInfo(frame: EdrFrame): FrameInfo =
    FrameInfo(frameId(frame), frame.kind.toString.toLowerCase, frame.validTime, frame.issued, frame.levelsFt)
}

/** GTGN/GTG data for the map: what's loaded, map tiles per frame and level, and point queries. */
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
  private def frame(id: String): Option[EdrFrame] = {
    val data = store.current
    (data.nowcast.toSeq ++ data.forecast).find(f => frameId(f) == id)
  }

  // The 3D volume is a few MB of JSON that compresses ~3x; PNG tiles are already compressed.
  override val routes: HttpRoutes[IO] =
    GZip(
      HttpRoutes.of[IO] {
        case GET -> Root / "status" =>
          val data = store.current
          val (south, west, north, east) = LambertGrid.boundingBox
          val status = TurbulenceStatus(
            data.nowcast.map(frameInfo),
            data.forecast.map(frameInfo),
            Thresholds.byClass.map { case (k, v) => k.toString -> v },
            Map("south" -> south, "west" -> west, "north" -> north, "east" -> east)
          )
          Ok(status.toJson.deepDropNullValues)

        case GET -> Root / "tiles" / id / IntVar(level) / IntVar(z) / IntVar(x) / TileY(y) =>
          frame(id).flatMap(_.nearestLayer(level)) match {
            case Some(layer) if z >= 0 && z <= 12 =>
              IO.blocking(tiles.render(s"$id/${layer.levelFt}", layer, z, x, y)).flatMap { png =>
                Ok(png).map(
                  _.withContentType(`Content-Type`(MediaType.image.png))
                    .putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(1.hour)))
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
              val s = step.getOrElse(6).max(3).min(24)
              IO.blocking(volumes.volume(id, f, s)).flatMap { v =>
                Ok(v.toJson).map(_.putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(1.hour))))
              }
            case None => NotFound("frame not loaded")
          }

        case GET -> Root / "column" / id :? LatParam(lat) +& LonParam(lon) =>
          frame(id) match {
            case Some(f) =>
              val index = LambertGrid.nearestIndex(lat, lon)
              val levels = f.levels.toSeq.map { case (ft, layer) =>
                val edr = layer.edrAtIndex(index)
                ColumnLevel(ft, Option(edr).filterNot(_.isNaN), Thresholds.classify(edr))
              }
              Ok(TurbulenceColumn(id, lat, lon, levels).toJson.deepDropNullValues)
            case None => NotFound("frame not loaded")
          }
      },
      isZippable = _.contentType.exists(_.mediaType == MediaType.application.json)
    )
}
