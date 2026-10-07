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
import io.circe.Encoder
import org.http4s.circe.*
import org.http4s.headers.{`Cache-Control`, `Content-Type`}
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

object TurbulenceEndpoint {
  given Encoder[EdrSource] = Encoder.encodeString.contramap(_.toString)

  def frameId(frame: EdrFrame): String = frame.kind match {
    case FrameKind.Nowcast => s"n${frame.validTime.getEpochSecond}"
    case FrameKind.Forecast => s"f${frame.issued.getEpochSecond}-${frame.validTime.getEpochSecond}"
  }

  def frameInfo(frame: EdrFrame): FrameInfo =
    FrameInfo(frameId(frame), frame.kind.toString.toLowerCase, frame.validTime, frame.issued, frame.levelsFt)
}

/** GTGN/GTG data for the map: what's loaded, map tiles per frame and level, and point queries. */
final class TurbulenceEndpoint(store: TurbulenceStore, tiles: TileRenderer) extends Endpoint with BackendSerialization {
  import TurbulenceEndpoint.{*, given}

  private object LatParam extends QueryParamDecoderMatcher[Double]("lat")
  private object LonParam extends QueryParamDecoderMatcher[Double]("lon")
  private object AltParam extends QueryParamDecoderMatcher[Double]("alt")
  private object TimeParam extends OptionalQueryParamDecoderMatcher[Long]("time")
  private object TileY {
    def unapply(s: String): Option[Int] = s.stripSuffix(".png").toIntOption
  }

  override val basePath: String = "/turbulence"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
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
      val data = store.current
      (data.nowcast.toSeq ++ data.forecast).find(f => frameId(f) == id).flatMap(_.nearestLayer(level)) match {
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
  }
}
