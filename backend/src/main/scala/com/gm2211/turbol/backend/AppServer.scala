/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend

import cats.Applicative
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.gm2211.turbol.backend.config.{InstallConfig, ServerConfig}
import com.gm2211.turbol.backend.endpoints.*
import com.gm2211.turbol.backend.logging.{BackendLogger, BackendLogging}
import com.gm2211.turbol.backend.server.RuntimeEnvTypes.AppTask
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.server.*
import org.http4s.implicits.*
import org.http4s.server.middleware.{CORS, ErrorHandling, Logger}
import org.http4s.server.{DefaultServiceErrorHandler, Router, Server}
import org.http4s.{Http, HttpApp, HttpRoutes}
import zio.*
import zio.interop.catz.*

import scala.concurrent.duration.*

object AppServer extends BackendLogging {

  def createServer(
    install: InstallConfig
  ): AppTask[Nothing] = {
    // TODO(gm2211): Need to figure out how to add Allow-Origin header to failed responses too, since CORS middleware
    //               is applied only to successful responses unless error handling is somehow applied before
    val maybeApplyCORS: Http[AppTask, AppTask] => Http[AppTask, AppTask] = {
      if install.server.devMode then {
        CORS
          .policy
          .withAllowMethodsAll
          .withAllowOriginAll
          .withAllowCredentials(false)
          .apply[AppTask, AppTask]
      } else {
        identity
      }
    }
    val endpoints = LazyList[Endpoint](AirportsEndpoint, FlightsEndpoint)
      .map(endpoint => s"/api/${endpoint.basePath.dropWhile(_ == '/')}" -> endpoint.routes)
      .toList
    val app = Router[AppTask](endpoints*)
    val port = Port.fromInt(install.server.port).get
    EmberServerBuilder
      .default[AppTask]
      .withHost(ipv4"0.0.0.0")
      .withPort(port)
      .withHttpApp(maybeApplyCORS(app.orNotFound))
      .build
      .useForever
  }
  end createServer
}
