/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.background.airportdata.*
import com.softwaremill.macwire.*

import scala.annotation.unused

@Module
final class BackgroundJobsModule(val storageModule: StorageModule, val servicesModule: ServicesModule) {
  @unused lazy val airportDataDownloader: AirportDataDownloader = wire[AirportDataDownloaderImpl]
  @unused lazy val airportRowParser: AirportDataParserFactory = wire[AirportDataParserFactoryImpl]
  
  // Jobs
  @unused lazy val airportDataUpdater: AirportDataUpdater = wire[AirportDataUpdater]
}
