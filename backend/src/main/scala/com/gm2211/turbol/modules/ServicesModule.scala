/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.services.{AirportsService, AirportsServiceImpl, SystemTimeService, TimeService}
import com.softwaremill.macwire.{wire, Module}

import scala.annotation.unused

@Module
class ServicesModule(@unused storageModule: StorageModule) {
  lazy val timeService: TimeService = wire[SystemTimeService]
  lazy val airportsService: AirportsService = wire[AirportsServiceImpl]
}
