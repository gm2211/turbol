/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.live.{LiveTrafficService, RouteScheduleService}
import com.gm2211.turbol.services.{AirportsService, AirportsServiceImpl, SystemTimeService, TimeService}
import com.gm2211.turbol.turbulence.{TileRenderer, TurbulenceDataUpdater, TurbulenceStore, VolumeSampler}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class ServicesModule(@unused storageModule: StorageModule) {
  lazy val timeService: TimeService = wire[SystemTimeService]
  lazy val airportsService: AirportsService = wire[AirportsServiceImpl]
  // Loading until the updater has checked each source once, so a restart doesn't hand out partial Flight Packs.
  lazy val turbulenceStore: TurbulenceStore = TurbulenceStore(loading = TurbulenceDataUpdater.sources)
  lazy val tileRenderer: TileRenderer = wire[TileRenderer]
  lazy val volumeSampler: VolumeSampler = VolumeSampler()
  lazy val liveTrafficService: LiveTrafficService = wire[LiveTrafficService]
  lazy val routeScheduleService: RouteScheduleService = RouteScheduleService(liveTrafficService)
}
