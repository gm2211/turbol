/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

import java.time.Instant
import java.time.temporal.ChronoUnit

class RouteCorridorTest extends BaseTest {
  import TurbulenceTestData.*

  private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
  private val store = new TurbulenceStore
  store.setForecast(
    Range.inclusive(0, 4)
      .map(h =>
        uniformFrame(FrameKind.Forecast, now.plus(h.toLong, ChronoUnit.HOURS), Map(30000 -> 0.05, 40000 -> 0.25))
      )
      .toVector
  )
  private val points = Seq(
    CorridorPoint(39.0, -98.0, 35000, now.plusSeconds(1800)),
    CorridorPoint(39.0, -97.5, 35000, now.plusSeconds(2400)),
    CorridorPoint(39.0, -97.0, 35000, now.plusSeconds(3000))
  )
  private val corridor = RouteCorridor.build(store, points)
  private val nl = corridor.levelsFt.length
  private val no = corridor.offsetsKm.length
  private def edr(k: Int, level: Int, offset: Int) =
    EdrLayer.decode(corridor.edr((k * nl + corridor.levelsFt.indexOf(level)) * no +
      corridor.offsetsKm.indexOf(offset.toDouble)))

  test("holds every level and offset at every point") {
    corridor.edr.length shouldBe 3 * 13 * 9
    corridor.levelsFt.head shouldBe -6000
    corridor.offsetsKm.last shouldBe 60.0
  }

  test("the centre of each cross-section is the route's own sample") {
    points.indices.foreach { k =>
      val p = points(k)
      edr(k, 0, 0) shouldBe store.sample(p.lat, p.lon, p.altitudeFt, p.time).edr +- 0.005
    }
  }

  test("levels above and below follow the forecast at those altitudes") {
    edr(1, 0, 0) shouldBe 0.15 +- 0.005
    edr(1, 2000, 0) shouldBe 0.19 +- 0.005
    edr(1, -6000, 0) shouldBe 0.05 +- 0.005
    edr(1, 2000, -60) shouldBe edr(1, 2000, 0)
  }
}

class SampleColumnTest extends BaseTest {
  import TurbulenceTestData.*

  private val now = Instant.now().truncatedTo(ChronoUnit.HOURS)
  private val store = new TurbulenceStore
  store.setForecast(
    Range.inclusive(0, 2)
      .map(h =>
        uniformFrame(FrameKind.Forecast, now.plus(h.toLong, ChronoUnit.HOURS), Map(30000 -> 0.05, 40000 -> 0.25))
      )
      .toVector
  )
  store.setGlobal(
    Range.inclusive(0, 3, 3)
      .map(h => uniformFrame(FrameKind.Global, now.plus(h.toLong, ChronoUnit.HOURS), Map(30000 -> 0.4, 40000 -> 0.1)))
      .toVector
  )

  test("a column matches sampling each altitude on its own, inside GTG, across its blended edge and outside it") {
    val data = store.current
    val altitudes = Range.inclusive(29000, 41000, 1000).map(_.toDouble)
    val places = Seq((39.0, -97.0), (21.5, -100.0), (48.0, -70.0), (50.5, -120.0), (45.6, 8.7), (-10.0, 30.0))
    for {
      (lat, lon) <- places
      minutes <- Seq(10L, 70L)
    } {
      val time = now.plusSeconds(minutes * 60)
      val column = store.sampleColumnIn(data, lat, lon, altitudes, time)
      altitudes.indices.foreach { l =>
        val single = store.sampleIn(data, lat, lon, altitudes(l), time).edr
        if (single.isNaN) column(l).isNaN shouldBe true else column(l) shouldBe single +- 1e-12
      }
    }
  }
}
