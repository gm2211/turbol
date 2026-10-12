/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.background.airportdata.*
import com.gm2211.turbol.turbulence.{FrameKind, TurbulenceDataUpdater}
import com.softwaremill.macwire.*

import scala.annotation.unused

@Module
final class BackgroundJobsModule(val storageModule: StorageModule, val servicesModule: ServicesModule) {
  @unused lazy val airportDataDownloader: AirportDataDownloader = wire[AirportDataDownloaderImpl]
  @unused lazy val airportRowParser: AirportDataParserFactory = wire[AirportDataParserFactoryImpl]

  // Jobs
  @unused lazy val airportDataUpdater: AirportDataUpdater = wire[AirportDataUpdater]
  lazy val turbulenceDataUpdater: TurbulenceDataUpdater = TurbulenceDataUpdater(
    servicesModule.turbulenceStore,
    onNewFrames = frames => {
      val store = servicesModule.turbulenceStore
      // The 3D volume of the frame the live map opens on first: it takes seconds on the free plan's CPU, the tiles a
      // minute, and a 3D link waits on the volume while 2D shows whatever tiles are ready.
      val data = store.current
      data.opening().foreach(f => servicesModule.volumeSampler.prewarm(f, data.globalNear(f.validTime)))
      // A new global cycle changes every map tile's edges; re-warm the one people open first, the nowcast.
      val warm = if (frames.forall(_.kind == FrameKind.Global)) store.current.nowcast.toSeq else frames
      warm.foreach(f => servicesModule.tileRenderer.prewarm(f, store.current.globalNear(f.validTime)))
    }
  )
}
