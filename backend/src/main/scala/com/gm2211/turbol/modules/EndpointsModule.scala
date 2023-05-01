package com.gm2211.turbol.modules

import com.gm2211.turbol.endpoints.AirportsEndpoint
import com.softwaremill.macwire.{Module, wire}

import scala.annotation.unused

@Module
class EndpointsModule(@unused servicesModule: ServicesModule) {
  lazy val airportsEndpoint: AirportsEndpoint = wire[AirportsEndpoint]
}
