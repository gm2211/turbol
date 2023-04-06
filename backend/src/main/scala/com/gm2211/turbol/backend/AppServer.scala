/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.Applicative
import cats.data.Kleisli
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.gm2211.logging.{BackendLogger, BackendLogging}
import com.gm2211.turbol.backend.config.install.{InstallConfig, ServerConfig}
import com.gm2211.turbol.backend.endpoints.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.middleware.*
import org.http4s.server.{DefaultServiceErrorHandler, Router, Server}
import org.http4s.{Http, HttpApp, HttpRoutes, Request, Response}

import scala.concurrent.duration.*

object AppServer extends BackendLogging {
  private type MyHttpApp = Kleisli[IO, Request[IO], Response[IO]]

  def createServer(install: InstallConfig): Resource[IO, Server] = {
    // TODO(gm2211): Need to figure out how to add Allow-Origin header to failed responses too, since CORS middleware
    //               is applied only to successful responses unless error handling is somehow applied before
    val maybeApplyCORS: MyHttpApp => MyHttpApp = {
      if install.server.devMode then {
        CORS
          .policy
          .withAllowMethodsAll
          .withAllowOriginAll
          .withAllowCredentials(false)
          .apply[IO, IO]
      } else {
        identity
      }
    }
    val endpoints = LazyList[Endpoint](AirportsEndpoint, FlightsEndpoint)
      .map(endpoint => s"/api/${endpoint.basePath.dropWhile(_ == '/')}" -> endpoint.routes)
      .toList
    val router: MyHttpApp = Router[IO](endpoints*).orNotFound
    val port = Port.fromInt(install.server.port).get
    val decorators: List[MyHttpApp => MyHttpApp] = LazyList
      .empty[MyHttpApp => MyHttpApp]
      .appended(ErrorHandling.Recover.total(_: MyHttpApp))
      .appended(RequestLogger.httpApp(logHeaders = true, logBody = true)(_: MyHttpApp))
      .appended(ResponseLogger.httpApp(logHeaders = true, logBody = false)(_: MyHttpApp))
      .appended(maybeApplyCORS)
      .toList
    val app: MyHttpApp = decorators.reduce((composedDecorator, fn) => composedDecorator.andThen(fn))(router)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port)
      .withHttpApp(app)
      .build
  }
}
