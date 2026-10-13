/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.std.Semaphore
import cats.effect.{IO, SyncIO}
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.{BackendSerialization, MoreExecutors}
import io.circe.generic.auto.*
import org.http4s.circe.*
import org.http4s.headers.{`Cache-Control`, `Content-Encoding`, `Content-Type`, `If-None-Match`, ETag}
import org.http4s.server.middleware.GZip
import org.http4s.{CacheDirective, ContentCoding, Header, HttpRoutes, MediaType, Request, Response, Status}
import org.typelevel.ci.CIString

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

  /** Characters per chunk of a streamed response body. */
  val bodyChunkChars: Int = 64 * 1024

  /**
   * Tile renders running at once. A panning map asks for dozens of tiles a second; unbounded, each render (raster, PNG
   * encoder, zlib buffers, a blocking thread) piles up off-heap until the 512 MB container is killed.
   */
  val tileRenders: Int = 2

  /**
   * A frame's public id names its GTG frame and the GFS cycle drawn under it, so tiles (cached for a week) change when
   * either does.
   */
  def frameId(frame: EdrFrame, data: TurbulenceData): String =
    data.globalNear(frame.validTime).fold(frame.id)(g => s"${frame.id}_g${g.issued.getEpochSecond}")

  /** Frame ids name immutable data, so what is served under one never needs to be asked for again. */
  val immutableCache: `Cache-Control` =
    `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(365.days), CacheDirective("immutable"))

  /** A tile's ETag: its frame (which includes the GFS cycle drawn under it), level and position name its pixels. */
  def tileETag(publicFrameId: String, level: Int, z: Int, x: Int, y: Int): ETag =
    ETag(ETag.EntityTag(s"$publicFrameId-$level-$z-$x-$y"))

  /** Whether the request already holds `etag` (or asked for any version, `If-None-Match: *`). */
  def hasETag[F[_]](req: Request[F], etag: ETag): Boolean =
    req.headers.get[`If-None-Match`].exists(_.tags.forall(_.exists(_.tag == etag.tag.tag)))

  private val gtgOutline: Seq[Seq[Double]] =
    LambertGrid.outline().map { case (lat, lon) => Seq(math.rint(lat * 100) / 100, math.rint(lon * 100) / 100) }

  /** Tile URLs may name this instead of a frame id, so the map can request tiles before it has fetched the status. */
  val latestNowcast = "latest"

  /**
   * The 3D volume URL may name this for the frame the live map opens on ([[TurbulenceData.opening]]), so the page can
   * start downloading it before it has fetched the status. The response names the frame in [[frameIdHeader]].
   */
  val openingFrame = "opening"
  val frameIdHeader = "X-Frame-Id"

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
  // Waiting requests queue as fibers here rather than as blocked threads.
  private val tileSlots: Semaphore[IO] = Semaphore.in[SyncIO, IO](tileRenders.toLong).unsafeRunSync()
  // Tiles served as last drawn are redrawn here, one at a time and at the lowest CPU priority, each once.
  private val redrawing = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  private val redrawer = MoreExecutors.background("tile-redraw", threads = 1).executionContext
  private def redraw(frame: EdrFrame, global: Option[EdrFrame], level: Int, z: Int, x: Int, y: Int): Unit = {
    val key = s"${frame.id}/${global.fold("-")(_.id)}/$level/$z/$x/$y"
    if (redrawing.add(key))
      redrawer.execute { () =>
        try tiles.render(frame, global, level, z, x, y): Unit
        finally redrawing.remove(key): Unit
      }
  }

  // A stale global suffix (the GFS cycle moved on since the client fetched the status) still finds its GTG frame.
  private def frame(id: String): Option[EdrFrame] = {
    val data = store.current
    if (id == latestNowcast) data.nowcast
    else if (id == openingFrame) data.opening()
    else (data.nowcast.toSeq ++ data.forecast).find(f => f.id == id.takeWhile(_ != '_'))
  }

  /**
   * A volume asked for by its frame's current public id never changes; "opening" moves with every new frame, and a
   * bare or stale id is still drawn with the current global layer.
   */
  private def volumeCacheControl(id: String, f: EdrFrame): `Cache-Control` =
    if (id == openingFrame) `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(1.minute))
    else if (id == frameId(f, store.current)) immutableCache
    else `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(1.hour))

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

        case req @ GET -> Root / "tiles" / id / IntVar(level) / IntVar(z) / IntVar(x) / TileY(y) =>
          frame(id) match {
            case Some(f) if z >= 0 && z <= 12 =>
              val data = store.current
              val global = data.globalNear(f.validTime)
              // Saved for nginx to serve the next time (see TileFiles); "latest" follows the nowcast asked for here.
              val publicId = frameId(f, data)
              // Only a request naming the frame's current public id gets an immutable tile: a bare or stale id is
              // still drawn with today's global layer, which can change under it.
              val etag = Option.when(id == publicId)(tileETag(publicId, level, z, x, y))
              def save(png: Array[Byte]): IO[Unit] = IO.blocking {
                TileFiles.write(publicId, level, z, x, y, png)
                if (id == latestNowcast) TileFiles.pointLatest(publicId)
              }
              // A frame id names immutable data; "latest" moves every 15 minutes.
              val tileMaxAge = 7.days
              val maxAge = if (id == latestNowcast) 1.minute else tileMaxAge
              // Rendered tiles (most of the opening view is prewarmed) skip the queue for a render slot. A tile whose
              // global layer just changed is served as last drawn, briefly cacheable, while it's redrawn behind it.
              val png = IO(tiles.cached(f, global, level, z, x, y)).flatMap {
                case Some(png) => save(png).as(png -> maxAge)
                case None =>
                  IO(tiles.previous(f, level, z, x, y)).flatMap {
                    case Some(png) => IO(redraw(f, global, level, z, x, y)).as(png -> 30.seconds)
                    case None =>
                      tileSlots.permit
                        .surround(IO.blocking(tiles.render(f, global, level, z, x, y)))
                        .flatTap(save)
                        .map(_ -> maxAge)
                  }
              }
              etag match {
                case Some(tag) if hasETag(req, tag) =>
                  IO.pure(Response[IO](Status.NotModified).putHeaders(tag, immutableCache))
                case _ =>
                  png.flatMap { (png, maxAge) =>
                    Ok(png).map { res =>
                      val typed = res.withContentType(`Content-Type`(MediaType.image.png))
                      etag match {
                        // Only a tile served from its own cache entry is final: one served as last drawn is not.
                        case Some(tag) if maxAge == tileMaxAge => typed.putHeaders(tag, immutableCache)
                        case _ =>
                          typed.putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(maxAge)))
                      }
                    }
                  }
              }
            case _ => NotFound("frame or level not loaded")
          }

        // The instance replacing this one on a deploy fetches the nowcast from here (see NowcastHandoff).
        case GET -> Root / "handoff" / "nowcast" =>
          NowcastHandoff.latest(TurbulenceDataUpdater.defaultDataDir) match {
            case Some(dir) =>
              val body = fs2.io.readOutputStream[IO](64 * 1024)(out => IO.blocking(NowcastHandoff.write(dir, out)))
              Ok(body).map(_.withContentType(`Content-Type`(MediaType.application.`octet-stream`)))
            case None => NotFound("no nowcast loaded")
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
              IO.blocking(volumes.volumeJson(f, global, s)).flatMap { json =>
                // In small chunks: one 12 MB body makes NIO copy it into a 12 MB direct buffer, outside the heap.
                val body: fs2.Stream[IO, Byte] = fs2.Stream
                  .range(0, json.length, TurbulenceEndpoint.bodyChunkChars)
                  .map(k => json.substring(k, math.min(json.length, k + TurbulenceEndpoint.bodyChunkChars)))
                  .through(fs2.text.utf8.encode)
                Ok(body).map(
                  _.withContentType(`Content-Type`(MediaType.application.json))
                    .putHeaders(volumeCacheControl(id, f))
                )
              }
            case None => NotFound("frame not loaded")
          }

        // The 3D map's form: binary, gzipped once when built (see Volume.toBinary2), so serving it costs no CPU. "bin"
        // is the older, larger Volume.toBinary, for pages loaded before "bin2" existed.
        case GET -> Root / "volume" / id / (format @ ("bin" | "bin2")) :? StepParam(step) =>
          frame(id) match {
            case Some(f) =>
              val s = step.getOrElse(VolumeSampler.defaultStep).max(VolumeSampler.minStep).min(24)
              val global = store.current.globalNear(f.validTime)
              IO.blocking(volumes.volumeGzip(f, global, s, if (format == "bin") 1 else 2)).flatMap { gz =>
                Ok(gz).map(
                  _.withContentType(`Content-Type`(MediaType.application.`octet-stream`))
                    .putHeaders(
                      `Content-Encoding`(ContentCoding.gzip),
                      volumeCacheControl(id, f),
                      Header.Raw(CIString(frameIdHeader), frameId(f, store.current))
                    )
                )
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
