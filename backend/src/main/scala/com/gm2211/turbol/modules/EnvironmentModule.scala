/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.services.{SystemTimeService, TimeService}
import com.softwaremill.macwire.{Module, wire}

@Module
class EnvironmentModule {
  lazy val timeService: TimeService = wire[SystemTimeService]
}
