/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.gm2211.turbol.backend.endpoints.{Endpoint, FlightsEndpoint}
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.{Router, Server}

import scala.concurrent.duration.*

object AppServer {
  private val routesAndPrefixes: Seq[(String, HttpRoutes[IO])] =
    LazyList[Endpoint](FlightsEndpoint)
      .map(endpoint =>
        s"/api/${endpoint.basePath.dropWhile(_ == '/')}" -> endpoint.routes
      )
      .toList
  private val httpApp = Router(routesAndPrefixes*).orNotFound

  val server: Resource[IO, Server] = EmberServerBuilder
    .default[IO]
    .withHost(ipv4"0.0.0.0")
    .withPort(port"8050")
    .withHttpApp(httpApp)
    .build
}
