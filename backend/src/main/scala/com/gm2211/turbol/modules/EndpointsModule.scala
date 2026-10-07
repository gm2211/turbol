/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.endpoints.{AirportsEndpoint, FlightsEndpoint, FrontendConfigEndpoint, LiveEndpoint, TurbulenceEndpoint}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class EndpointsModule(servicesModule: ServicesModule, @unused configModule: ConfigModule) {
  lazy val airportsEndpoint: AirportsEndpoint = wire[AirportsEndpoint]
  lazy val frontendConfigEndpoint: FrontendConfigEndpoint = wire[FrontendConfigEndpoint]
  lazy val turbulenceEndpoint: TurbulenceEndpoint =
    TurbulenceEndpoint(servicesModule.turbulenceStore, servicesModule.tileRenderer)
  lazy val flightsEndpoint: FlightsEndpoint =
    FlightsEndpoint(servicesModule.turbulenceStore, servicesModule.liveTrafficService)
  lazy val liveEndpoint: LiveEndpoint = LiveEndpoint(servicesModule.turbulenceStore, servicesModule.liveTrafficService)
}
