package com.gm2211.turbol

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import com.comcast.ip4s.Literals.ipv4
import com.comcast.ip4s.{ipv4, Port}
import com.gm2211.logging.BackendLogging
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.install.InstallConfig
import com.gm2211.turbol.config.runtime.RuntimeConfig
import com.gm2211.turbol.endpoints.{AirportsEndpoint, Endpoint, FlightsEndpoint}
import com.gm2211.turbol.modules.AppModule
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{CORS, ErrorHandling, RequestLogger, ResponseLogger}
import org.http4s.server.{Router, Server}
import org.http4s.{Request, Response}

object AppServer extends BackendLogging {
  private type MyHttpApp = Kleisli[IO, Request[IO], Response[IO]]

  def createServer(appModule: AppModule): Resource[IO, Server] = {
    val install: InstallConfig = appModule.configModule.install

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
