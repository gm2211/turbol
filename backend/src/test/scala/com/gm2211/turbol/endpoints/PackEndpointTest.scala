/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BaseTest
import io.circe.parser.parse
import org.http4s.headers.{`Accept-Encoding`, `Cache-Control`, `Content-Encoding`, `Content-Type`, `Retry-After`}
import org.http4s.server.Router
import org.http4s.{ContentCoding, MediaType, Method, Request, Response, Status, Uri}

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPInputStream
import scala.collection.immutable.SortedMap

class PackEndpointTest extends BaseTest {
  private val issued = Instant.parse("2026-01-15T12:00:00Z")
  private val store = new TurbulenceStore()
  store.setForecast(
    Range
      .inclusive(1, 18)
      .map(h => EdrFrame(FrameKind.Forecast, issued.plus(h.toLong, ChronoUnit.HOURS), issued, SortedMap.empty))
      .toVector
  )
  private val gfsIssued = issued.minus(6, ChronoUnit.HOURS)
  store.setGlobal(
    Range
      .inclusive(0, 8)
      .map(k => EdrFrame(FrameKind.Global, issued.plus(3L * k, ChronoUnit.HOURS), gfsIssued, SortedMap.empty))
      .toVector,
    wafsIssued = Some(gfsIssued)
  )
  private val app =
    Router[IO]("/api/packs" -> PackEndpoint(store).routes, "/api/ping" -> PingEndpoint().routes).orNotFound

  private def get(uri: String, gzip: Boolean = false): Response[IO] = {
    val req = Request[IO](Method.GET, Uri.unsafeFromString(uri))
    app.run(if (gzip) req.putHeaders(`Accept-Encoding`(ContentCoding.gzip)) else req).unsafeRunSync()
  }
  private def body(r: Response[IO]): Array[Byte] = r.body.compile.to(Array).unsafeRunSync()

  private val manifestUri = "/api/packs/manifest?o=40.6413,-73.7781&d=33.9416,-118.4085&dep=2026-01-15T14:00:00Z"

  test("the manifest lists one slab per usable frame, and each slab URL serves that slab") {
    val response = get(manifestUri)
    response.status shouldBe Status.Ok
    response.headers.get[`Cache-Control`].map(_.values.toList.map(_.toString)) shouldBe Some(List("no-cache"))
    val json = parse(new String(body(response), "UTF-8")).toOption.get
    val cols = json.hcursor.get[Int]("cols").toOption.get
    cols shouldBe 81
    val frames = json.hcursor.downField("frames").values.get.toVector
    // Nine GTG forecast hours, and the global frames from 12:00Z to 21:00Z for flying outside or past GTG.
    frames.map(_.hcursor.get[String]("kind").toOption.get).groupBy(identity).view.mapValues(_.size).toMap shouldBe
      Map("forecast" -> 9, "global" -> 4)
    json.hcursor.get[Instant]("globalIssued").toOption shouldBe Some(gfsIssued)
    json.hcursor.get[Instant]("wafsIssued").toOption shouldBe Some(gfsIssued)
    val globalUrl = frames.last.hcursor.get[String]("url").toOption.get
    globalUrl should startWith(s"/api/packs/slab/${store.current.global(3).id}?")
    get(globalUrl, gzip = true).status shouldBe Status.Ok
    val first = frames.head.hcursor
    val (rowStart, rowEnd) = (first.get[Int]("rowStart").toOption.get, first.get[Int]("rowEnd").toOption.get)
    val url = first.get[String]("url").toOption.get

    val zipped = get(url, gzip = true)
    zipped.status shouldBe Status.Ok
    zipped.headers.get[`Content-Encoding`].map(_.contentCoding) shouldBe Some(ContentCoding.gzip)
    zipped.headers.get[`Cache-Control`].map(_.values.toList.map(_.toString)).get should contain("immutable")
    val bytes = new GZIPInputStream(new ByteArrayInputStream(body(zipped))).readAllBytes()
    bytes.length shouldBe FlightPack.levelsFt.size * (rowEnd - rowStart) * cols
    // No EDR is loaded for these frames, so every cell decodes as missing.
    val decoded = FlightPack.decode(bytes.clone(), rowEnd - rowStart, cols)
    java.util.Arrays.equals(decoded, Array.fill(decoded.length)(EdrLayer.missing)) shouldBe true

    val plain = get(url)
    plain.status shouldBe Status.Ok
    plain.headers.get[`Content-Encoding`] shouldBe None
    body(plain) shouldBe bytes
  }

  test("just after the server starts, the manifest asks the phone to come back until every source is checked") {
    val starting = new TurbulenceStore(loading = TurbulenceDataUpdater.sources)
    // The first GTG hours are in, the rest and the other sources on their way.
    starting.setForecast(store.current.forecast.take(2))
    val routes = Router[IO]("/api/packs" -> PackEndpoint(starting).routes).orNotFound
    def manifest() = routes.run(Request[IO](Method.GET, Uri.unsafeFromString(manifestUri))).unsafeRunSync()

    val waiting = manifest()
    waiting.status shouldBe Status.ServiceUnavailable
    waiting.headers.get[`Retry-After`].map(_.retry) shouldBe Some(Right(PackEndpoint.retryAfterSeconds))
    waiting.headers.get[`Cache-Control`].map(_.values.toList.map(_.toString)) shouldBe Some(List("no-store"))
    // JSON, so the phone can tell it from a captive portal or the host's own error page.
    waiting.headers.get[`Content-Type`].map(_.mediaType) shouldBe Some(MediaType.application.json)
    val json = parse(new String(body(waiting), "UTF-8")).toOption.get
    json.hcursor.get[Boolean]("loading") shouldBe Right(true)
    json.hcursor.get[Seq[String]]("sources") shouldBe Right(Seq("forecast", "global", "nowcast"))

    // A source counts once checked, whether or not it found data.
    starting.loaded("nowcast")
    starting.loaded("forecast")
    manifest().status shouldBe Status.ServiceUnavailable
    starting.loaded("global")
    manifest().status shouldBe Status.Ok
  }

  test("bad requests say what is wrong") {
    get("/api/packs/manifest?o=40.6413,-73.7781&d=40.6414,-73.7782&dep=2026-01-15T14:00:00Z").status shouldBe
      Status.BadRequest
    get("/api/packs/manifest?o=nope&d=33.9416,-118.4085&dep=2026-01-15T14:00:00Z").status shouldBe Status.BadRequest
    get("/api/packs/slab/f-unknown?o=40.6413,-73.7781&d=33.9416,-118.4085&rows=0-10").status shouldBe Status.NotFound
    val frameId = store.current.forecast.head.id
    get(s"/api/packs/slab/$frameId?o=40.6413,-73.7781&d=33.9416,-118.4085&rows=0-100000").status shouldBe
      Status.NotFound
  }

  test("ping answers with a small, never-cached body") {
    val response = get("/api/ping")
    response.status shouldBe Status.Ok
    response.headers.get[`Cache-Control`].map(_.values.toList.map(_.toString)) shouldBe Some(List("no-store"))
    new String(body(response), "UTF-8") should startWith("""{"ok":"turbol","time":""")
  }
}
