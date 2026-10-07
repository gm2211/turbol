/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.live.LiveTrafficService
import com.gm2211.turbol.services.{AirportsService, AirportsServiceImpl, SystemTimeService, TimeService}
import com.gm2211.turbol.turbulence.{TileRenderer, TurbulenceStore}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class ServicesModule(@unused storageModule: StorageModule) {
  lazy val timeService: TimeService = wire[SystemTimeService]
  lazy val airportsService: AirportsService = wire[AirportsServiceImpl]
  lazy val turbulenceStore: TurbulenceStore = wire[TurbulenceStore]
  lazy val tileRenderer: TileRenderer = wire[TileRenderer]
  lazy val liveTrafficService: LiveTrafficService = wire[LiveTrafficService]
}
