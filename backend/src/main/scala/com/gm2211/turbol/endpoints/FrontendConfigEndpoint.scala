package com.gm2211.turbol.endpoints

import cats.effect.IO
import com.gm2211.turbol.util.BackendSerialization
import io.circe.Json
import org.http4s.HttpRoutes

final case class FrontendConfig()

final class FrontendConfigEndpoint() extends Endpoint with BackendSerialization {
  override val basePath: String = "/config"
  override val routes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root  =>
    val responseJson: Json = FrontendConfig()
    Ok(responseJson.noSpaces)
  }
}
