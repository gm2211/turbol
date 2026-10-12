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
import org.http4s.headers.{`Accept-Encoding`, `Cache-Control`, `Content-Encoding`}
import org.http4s.server.Router
import org.http4s.{ContentCoding, Method, Request, Response, Status, Uri}

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
    frames.size shouldBe 9
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
