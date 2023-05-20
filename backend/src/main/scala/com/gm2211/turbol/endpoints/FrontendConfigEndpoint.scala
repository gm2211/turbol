package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Json
import io.circe.generic.auto.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*

final case class FrontendConfig(domainLockedMapboxToken: String)

final class FrontendConfigEndpoint(secrets: () => AppSecrets) extends Endpoint with BackendSerialization {
  override val basePath: String = "/config"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "config" =>
    val responseJson: Json = FrontendConfig(domainLockedMapboxToken = secrets().mapboxToken).toJson
    Ok(responseJson.noSpaces)
  }
}
