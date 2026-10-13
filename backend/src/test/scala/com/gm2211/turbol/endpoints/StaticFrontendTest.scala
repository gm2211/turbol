/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.util.BaseTest
import org.http4s.headers.{`Cache-Control`, `Content-Type`}
import org.http4s.{Method, Request, Response, Status, Uri}

import java.nio.file.{Files, Path}

class StaticFrontendTest extends BaseTest {
  private val dir: Path = Files.createTempDirectory("turbol-static")
  Files.createDirectories(dir.resolve("assets"))
  Files.writeString(dir.resolve("index.html"), "<!doctype html><title>Turbol</title>")
  Files.writeString(dir.resolve("assets/index-abc123.js"), "console.log('turbol')")
  Files.writeString(dir.resolve("sw.js"), "self.addEventListener('fetch', () => {})")
  Files.writeString(dir.resolve("robots.txt"), "User-agent: *\nDisallow: /api/\n")
  Files.writeString(dir.resolve("manifest.webmanifest"), """{"name":"Turbol"}""")
  private val app = StaticFrontend.routes(dir.toString).orNotFound

  private def get(path: String): Response[IO] =
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(path))).unsafeRunSync()
  private def cacheControl(r: Response[IO]): List[String] =
    r.headers.get[`Cache-Control`].toList.flatMap(_.values.toList.map(_.toString))
  private def contentType(r: Response[IO]): String =
    r.headers.get[`Content-Type`].map(c => s"${c.mediaType.mainType}/${c.mediaType.subType}").getOrElse("")

  test("fingerprinted files are kept for a year, and a missing one is a 404, not index.html") {
    val js = get("/assets/index-abc123.js")
    js.status shouldBe Status.Ok
    contentType(js) should include("javascript")
    cacheControl(js) should contain("immutable")
    get("/assets/index-gone99.js").status shouldBe Status.NotFound
  }

  test("deep links get index.html, and the service worker and manifest are always revalidated") {
    val deepLink = get("/map")
    deepLink.status shouldBe Status.Ok
    contentType(deepLink) shouldBe "text/html"
    cacheControl(deepLink) shouldBe List("no-cache")
    val sw = get("/sw.js")
    contentType(sw) should include("javascript")
    cacheControl(sw) shouldBe List("no-cache")
    val manifest = get("/manifest.webmanifest")
    manifest.status shouldBe Status.Ok
    contentType(manifest) shouldBe "application/manifest+json"
    cacheControl(manifest) shouldBe List("no-cache")
    get("/api/nothing").status shouldBe Status.NotFound
  }

  test("robots.txt is served as the file, not the app's index.html") {
    val robots = get("/robots.txt")
    robots.status shouldBe Status.Ok
    contentType(robots) shouldBe "text/plain"
    robots.as[String].unsafeRunSync() should include("Disallow: /api/")
  }
}
