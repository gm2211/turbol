package com.gm2211.turbol.modules

import com.gm2211.turbol.endpoints.{AirportsEndpoint, FrontendConfigEndpoint}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class EndpointsModule(@unused servicesModule: ServicesModule) {
  lazy val airportsEndpoint: AirportsEndpoint = wire[AirportsEndpoint]
  lazy val frontendConfigEndpoint: FrontendConfigEndpoint = wire[FrontendConfigEndpoint]
}
