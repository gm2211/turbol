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
import org.http4s.{HttpRoutes, StaticFile}

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
    fileService[IO](FileService.Config[IO](dir)) <+> spaFallback
  }
}
