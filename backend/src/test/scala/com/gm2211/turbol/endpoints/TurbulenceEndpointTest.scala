/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.turbulence.*
import com.gm2211.turbol.util.BaseTest
import org.http4s.headers.{`Cache-Control`, `If-None-Match`, ETag}
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.time.Instant
import java.time.temporal.ChronoUnit

class TurbulenceEndpointTest extends BaseTest {
  import TurbulenceTestData.*

  private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
  private val store = new TurbulenceStore
  store.setNowcast(uniformFrame(FrameKind.Nowcast, now, Map(35000 -> 0.2)))
  store.setForecast(
    Range.inclusive(1, 3).map(h =>
      uniformFrame(FrameKind.Forecast, now.plus(h.toLong, ChronoUnit.HOURS), Map(35000 -> 0.2))
    ).toVector
  )
  private val volumeFrame = store.current.forecast.head
  private val frameId = TurbulenceEndpoint.frameId(volumeFrame, store.current)
  private val app = TurbulenceEndpoint(store, TileRenderer(), VolumeSampler()).routes.orNotFound

  private def get(path: String, headers: Header.ToRaw*): Response[IO] =
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)).putHeaders(headers*)).unsafeRunSync()
  private def cacheControl(r: Response[IO]): String =
    r.headers.get[`Cache-Control`].map(_.values.toList.map(_.toString).mkString(", ")).getOrElse("")

  test("a frame's tile is cached for a year, immutable, with an ETag naming frame, level and position") {
    val res = get(s"/tiles/$frameId/35000/4/3/5.png")
    res.status shouldBe Status.Ok
    cacheControl(res) should (include("public") and include("max-age=31536000") and include("immutable"))
    res.headers.get[ETag].map(_.tag.tag) shouldBe Some(s"$frameId-35000-4-3-5")
  }

  test("a tile whose ETag the browser holds is answered 304, without a body") {
    val etag = get(s"/tiles/$frameId/35000/4/3/5.png").headers.get[ETag].get
    val res = get(s"/tiles/$frameId/35000/4/3/5.png", `If-None-Match`(etag.tag))
    res.status shouldBe Status.NotModified
    res.body.compile.toList.unsafeRunSync() shouldBe empty
    res.headers.get[ETag] shouldBe Some(etag)
    cacheControl(res) should include("immutable")
    // Another tile's ETag doesn't match.
    val other = get(s"/tiles/$frameId/35000/4/3/6.png", `If-None-Match`(etag.tag))
    other.status shouldBe Status.Ok
  }

  test("the moving 'latest' tile is cached briefly and has no ETag") {
    val res = get("/tiles/latest/35000/4/3/5.png")
    res.status shouldBe Status.Ok
    cacheControl(res) should (include("max-age=60") and not include "immutable")
    res.headers.get[ETag] shouldBe None
  }

  test("a frame's 3D volume is cached for a week, immutable; 'opening' only for a minute") {
    val volume = get(s"/volume/$frameId/bin2")
    volume.status shouldBe Status.Ok
    cacheControl(volume) should (include("max-age=31536000") and include("immutable"))
    val opening = get("/volume/opening/bin2")
    opening.status shouldBe Status.Ok
    cacheControl(opening) should (include("max-age=60") and not include "immutable")
    opening.headers.get(CIString(TurbulenceEndpoint.frameIdHeader)).isDefined shouldBe true
  }
}
