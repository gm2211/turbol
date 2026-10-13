/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.background.airportdata.*
import com.gm2211.turbol.endpoints.TurbulenceEndpoint
import com.gm2211.turbol.turbulence.{FrameKind, TileFiles, TurbulenceDataUpdater}
import com.gm2211.turbol.util.Visitors
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
      data.opening().foreach { f =>
        Visitors.waitForQuiet()
        servicesModule.volumeSampler.prewarm(f, data.globalNear(f.validTime))
      }
      // A new global cycle changes every map tile's edges; re-warm the one people open first, the nowcast. It goes
      // last, so a forecast's 18 hours of tiles don't push it out of the tile cache.
      val nowcast = store.current.nowcast.toSeq
      val warm = if (frames.forall(_.kind == FrameKind.Global)) nowcast
      else frames.filterNot(f => nowcast.exists(_.id == f.id)) ++ nowcast
      // Each also saved for nginx to serve (TileFiles); once the nowcast's are all there, nginx's "latest" moves to it.
      warm.foreach { f =>
        val data = store.current
        val id = TurbulenceEndpoint.frameId(f, data)
        servicesModule.tileRenderer
          .prewarm(f, data.globalNear(f.validTime), save = (z, x, y, png) => TileFiles.write(id, 35000, z, x, y, png))
        if (nowcast.exists(_.id == f.id)) TileFiles.pointLatest(id)
      }
    }
  )
}
