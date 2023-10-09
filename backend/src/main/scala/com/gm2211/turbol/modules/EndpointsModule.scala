/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.endpoints.{AirportsEndpoint, FrontendConfigEndpoint}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class EndpointsModule(@unused servicesModule: ServicesModule, @unused configModule: ConfigModule) {
  lazy val airportsEndpoint: AirportsEndpoint = wire[AirportsEndpoint]
  lazy val frontendConfigEndpoint: FrontendConfigEndpoint = wire[FrontendConfigEndpoint]
}
