/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.endpoints

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import org.http4s.dsl.io.*
import org.http4s.server.staticcontent.{fileService, FileService}
import org.http4s.headers.`Cache-Control`
import org.http4s.{CacheDirective, HttpRoutes, StaticFile}

import scala.concurrent.duration.*

/**
 * Serves the built frontend (`frontend/dist`) so one service hosts the whole app. Paths that aren't files fall back
 * to index.html (except under /api), letting the Vue router handle deep links such as /follow/UA1517.
 */
object StaticFrontend {
  def routes(dir: String): HttpRoutes[IO] = {
    val index = Path(dir) / "index.html"
    val spaFallback = HttpRoutes.of[IO] {
      case GET -> "api" /: _ => NotFound()
      case req @ GET -> _ => StaticFile.fromPath(index, Some(req)).getOrElseF(NotFound())
    }
    // Vite fingerprints everything under /assets, so browsers can keep it; index.html must be revalidated.
    val files = HttpRoutes[IO] { req =>
      fileService[IO](FileService.Config[IO](dir)).run(req).map { res =>
        if (req.pathInfo.renderString.startsWith("/assets/"))
          res.putHeaders(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(365.days), immutable))
        else res.putHeaders(`Cache-Control`(CacheDirective.`no-cache`()))
      }
    }
    val revalidate = HttpRoutes[IO](req => spaFallback.run(req).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-cache`()))))
    files <+> revalidate
  }

  private val immutable = CacheDirective("immutable")
}
