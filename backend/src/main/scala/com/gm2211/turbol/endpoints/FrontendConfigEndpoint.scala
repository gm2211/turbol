package com.gm2211.turbol.endpoints

import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.util.BackendSerialization

case class FrontendConfig(domainLockedMapboxToken: String)

class FrontendConfigEndpoint(secrets: => AppSecrets) extends Endpoint with BackendSerialization {
  override val basePath: String = "/config"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "config" =>
    val responseJson: IO[Json] = IO {
      FrontendConfig(domainLockedMapboxToken = secrets.mapboxToken).toJson
    }
    Ok(responseJson)
  }
}
