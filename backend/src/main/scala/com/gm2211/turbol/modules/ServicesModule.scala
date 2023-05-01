package com.gm2211.turbol.modules

import com.gm2211.turbol.services.{AirportsService, AirportsServiceImpl, SystemTimeService, TimeService}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class ServicesModule(@unused storageModule: StorageModule) {
  lazy val timeService: TimeService = wire[SystemTimeService]
  lazy val airportsService: AirportsService = wire[AirportsServiceImpl]
}
