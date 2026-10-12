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
      // A new global cycle changes every map tile's edges; re-warm the one people open first, the nowcast.
      val warm = if (frames.forall(_.kind == FrameKind.Global)) store.current.nowcast.toSeq else frames
      warm.foreach(f => servicesModule.tileRenderer.prewarm(f, store.current.globalNear(f.validTime)))
      // And the 3D volume of the frame the live map opens on: the nowcast, else the first forecast hour more than
      // 15 minutes ahead (LiveMapView's frames).
      val data = store.current
      val soon = java.time.Instant.now().plusSeconds(15 * 60)
      val opening = data.nowcast.orElse(data.forecast.find(_.validTime.isAfter(soon)))
      opening.foreach { f =>
        servicesModule.volumeSampler.prewarm(f, data.globalNear(f.validTime))
      }
    }
  )
}
