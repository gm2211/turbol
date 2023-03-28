/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.gm2211.turbol.backend.config.InstallConfig
import com.gm2211.turbol.backend.endpoints.{AirportsEndpoint, Endpoint, FlightsEndpoint}
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.middleware.{CORS, ErrorHandling, Logger}
import org.http4s.server.{DefaultServiceErrorHandler, Router, Server}

import scala.concurrent.duration.*

class AppServer(install: InstallConfig) {
  private val routesAndPrefixes: Seq[(String, HttpRoutes[IO])] =
    LazyList[Endpoint](AirportsEndpoint, FlightsEndpoint)
      .map(endpoint => s"/api/${endpoint.basePath.dropWhile(_ == '/')}" -> endpoint.routes)
      .toList
  private val allRoutes = Router(routesAndPrefixes*)
  // TODO(gm2211): Need to figure out how to add Allow-Origin header to failed responses too, since CORS middleware
  //               is applied only to successful responses unless error handling is somehow applied before
  private val httpApp: HttpRoutes[IO] = if install.devMode then {
    val corsMiddleware = CORS
      .policy
      .withAllowMethodsAll
      .withAllowOriginAll
      .withAllowCredentials(false)
    corsMiddleware(allRoutes)
  } else {
    allRoutes
  }

  def createServer: Resource[IO, Server] = EmberServerBuilder
    .default[IO]
    .withHost(ipv4"0.0.0.0")
    .withPort(port"8081")
    .withHttpApp(httpApp.orNotFound)
    .build
}
