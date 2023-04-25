package com.gm2211.turbol.modules

import com.gm2211.turbol.background.airportdata.*
import com.softwaremill.macwire.*

import scala.annotation.unused

@Module
final class BackgroundJobsModule(val storageModule: StorageModule) {
  @unused lazy val airportDataDownloader: AirportDataDownloader = wire[AirportDataDownloaderImpl.type]
  @unused lazy val airportRowParser: AirportDataParserFactory = wire[AirportDataParserFactory.type]
  @unused lazy val airportDataUpdater = wire[AirportDataUpdater]
}
