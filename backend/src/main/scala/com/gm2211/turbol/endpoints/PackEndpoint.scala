/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BackendSerialization
import com.google.common.cache.{Cache, CacheBuilder, Weigher}
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.headers.{`Accept-Encoding`, `Cache-Control`, `Content-Encoding`, `Content-Type`}
import org.http4s.{CacheDirective, ContentCoding, Header, HttpRoutes, MediaType, Request}
import org.typelevel.ci.*

import java.time.Instant
import java.util.Locale
import scala.concurrent.duration.*
import scala.util.Try

final case class PackPoint(lat: Double, lon: Double)
final case class PackFrameInfo(
  id: String,
  kind: String,
  validTime: Instant,
  issued: Instant,
  rowStart: Int,
  rowEnd: Int,
  url: String
)

/** What a phone needs to download a flight's pack: the grid, the levels, and one slab URL per frame. */
final case class PackManifest(
  version: Int,
  origin: PackPoint,
  destination: PackPoint,
  departure: Instant,
  totalKm: Double,
  rows: Int,
  cols: Int,
  alongStepKm: Double,
  crossStepKm: Double,
  levelsFt: Seq[Int],
  frames: Seq[PackFrameInfo],
  nowcastValid: Option[Instant],
  forecastIssued: Option[Instant],
  forecastUntil: Option[Instant],
  globalIssued: Option[Instant],
  /** The WAFS cycle blended into the global layer from FL140 up, when one was available. */
  wafsIssued: Option[Instant],
  thresholds: Map[String, Thresholds],
  generatedAt: Instant
)

object PackEndpoint {
  /** Coordinates go into slab URLs, so they are rounded (to ~10 m) and printed the same way every time. */
  def formatPoint(lat: Double, lon: Double): String = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon)

  def parsePoint(s: String): Option[PackPoint] =
    s.split(',') match {
      case Array(la, lo) =>
        for {
          lat <- la.trim.toDoubleOption if lat >= -90 && lat <= 90
          lon <- lo.trim.toDoubleOption if lon >= -180 && lon <= 180
        } yield {
          val rounded = formatPoint(lat, lon).split(',')
          PackPoint(rounded(0).toDouble, rounded(1).toDouble)
        }
      case _ => None
    }

  def parseInstant(s: String): Option[Instant] =
    Try(Instant.parse(s)).toOption.orElse(s.toLongOption.map(Instant.ofEpochSecond))

  def slabUrl(o: PackPoint, d: PackPoint, slab: PackSlab): String =
    s"/api/packs/slab/${slab.frame.id}?o=${formatPoint(o.lat, o.lon)}&d=${formatPoint(d.lat, d.lon)}" +
      s"&rows=${slab.rowStart}-${slab.rowEnd}&v=${FlightPack.version}"

  def manifest(
    grid: PackGrid,
    data: TurbulenceData,
    o: PackPoint,
    d: PackPoint,
    departure: Instant,
    now: Instant
  ): PackManifest = {
    val slabs = FlightPack.slabs(grid, data, departure)
    PackManifest(
      FlightPack.version,
      o,
      d,
      departure,
      grid.totalKm,
      grid.rows,
      grid.cols,
      grid.alongStepKm,
      grid.crossStepKm,
      FlightPack.levelsFt,
      slabs.map(s =>
        PackFrameInfo(
          s.frame.id,
          s.frame.kind.toString.toLowerCase,
          s.frame.validTime,
          s.frame.issued,
          s.rowStart,
          s.rowEnd,
          slabUrl(o, d, s)
        )
      ),
      data.nowcast.map(_.validTime),
      data.forecastIssued,
      data.forecast.lastOption.map(_.validTime),
      data.globalIssued,
      data.globalWafsIssued,
      Thresholds.byClass.map { case (k, v) => k.toString -> v },
      now
    )
  }
}

/**
 * Flight Packs: everything a phone needs to work out a flight's turbulence offline (see [[FlightPack]]). The manifest
 * changes with every nowcast and GTG cycle; a slab is named by its frame id and rows, so it never changes and phones
 * and caches may keep it for good.
 */
final class PackEndpoint(store: TurbulenceStore) extends Endpoint with BackendSerialization {
  import PackEndpoint.*

  private object Origin extends QueryParamDecoderMatcher[String]("o")
  private object Destination extends QueryParamDecoderMatcher[String]("d")
  private object Departure extends QueryParamDecoderMatcher[String]("dep")
  private object Rows extends QueryParamDecoderMatcher[String]("rows")

  // Gzipped slabs, so a retried or shared download isn't rebuilt on the 0.1-CPU instance.
  private val gzipped: Cache[String, Array[Byte]] = CacheBuilder
    .newBuilder()
    .maximumWeight(16L * 1024 * 1024)
    .weigher(new Weigher[String, Array[Byte]] { def weigh(k: String, v: Array[Byte]): Int = v.length })
    .build()

  private val immutable =
    `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(365.days), CacheDirective("immutable"))
  private val octetStream = `Content-Type`(MediaType.application.`octet-stream`)

  override val basePath: String = "/packs"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "manifest" :? Origin(o) +& Destination(d) +& Departure(dep) =>
      (parsePoint(o), parsePoint(d), parseInstant(dep)) match {
        case (Some(from), Some(to), Some(departure)) if routeKm(from, to) >= FlightPack.minRouteKm =>
          val grid = PackGrid(from.lat, from.lon, to.lat, to.lon)
          val m = manifest(grid, store.current, from, to, departure, Instant.now())
          Ok(m.toJson.deepDropNullValues).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-cache`())))
        case (Some(_), Some(_), Some(_)) => BadRequest("origin and destination are too close for a pack")
        case _ => BadRequest("expected o=lat,lon&d=lat,lon&dep=<ISO time or epoch seconds>")
      }

    case req @ GET -> Root / "slab" / frameId :? Origin(o) +& Destination(d) +& Rows(rows) =>
      val slab = for {
        from <- parsePoint(o)
        to <- parsePoint(d) if routeKm(from, to) >= FlightPack.minRouteKm
        grid = PackGrid(from.lat, from.lon, to.lat, to.lon)
        (start, end) <- parseRows(rows) if start >= 0 && end > start && end <= grid.rows
        data = store.current
        frame <- (data.nowcast.toSeq ++ data.forecast ++ data.global).find(_.id == frameId)
      } yield (
        grid,
        PackSlab(frame, start, end),
        s"$frameId|${formatPoint(from.lat, from.lon)}|${formatPoint(to.lat, to.lon)}|$rows"
      )
      slab match {
        case None => NotFound("frame not loaded or bad slab parameters: fetch the manifest again")
        case Some((grid, s, key)) =>
          if (acceptsGzip(req))
            IO.blocking(gzipped.get(key, () => FlightPack.gzip(FlightPack.encode(grid, s)))).flatMap { body =>
              Ok(body).map(
                _.putHeaders(
                  octetStream,
                  immutable,
                  `Content-Encoding`(ContentCoding.gzip),
                  Header.Raw(ci"Vary", "Accept-Encoding")
                )
              )
            }
          else
            IO.blocking(FlightPack.encode(grid, s)).flatMap { body =>
              Ok(body).map(_.putHeaders(octetStream, immutable, Header.Raw(ci"Vary", "Accept-Encoding")))
            }
      }
  }

  private def routeKm(a: PackPoint, b: PackPoint): Double = RouteAnalyzer.greatCircleKm(a.lat, a.lon, b.lat, b.lon)

  private def parseRows(s: String): Option[(Int, Int)] =
    s.split('-') match {
      case Array(a, b) => a.toIntOption.zip(b.toIntOption)
      case _ => None
    }

  private def acceptsGzip(req: Request[IO]): Boolean =
    req.headers.get[`Accept-Encoding`].exists(_.satisfiedBy(ContentCoding.gzip))
}

/** A tiny, never-cached response the app uses to tell a working connection from a captive portal or a dead link. */
final class PingEndpoint extends Endpoint {
  override val basePath: String = "/ping"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root =>
    Ok(s"""{"ok":"turbol","time":${Instant.now().toEpochMilli}}""").map(
      _.withContentType(`Content-Type`(MediaType.application.json))
        .putHeaders(`Cache-Control`(CacheDirective.`no-store`))
    )
  }
}
