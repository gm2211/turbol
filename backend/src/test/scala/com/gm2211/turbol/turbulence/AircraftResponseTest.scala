/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import com.gm2211.turbol.util.BaseTest

class AircraftResponseTest extends BaseTest {
  private def of(t: String) = AircraftResponse.fromAdsb(None, Some(t))

  test("the A320 is the reference aircraft") {
    of("A320").factor shouldBe 1.0
    of("A320").basis shouldBe "type"
    of("A320").wingLoadingKgM2 shouldBe Some(541.0)
  }

  test("higher wing loading feels the same EDR less, lower wing loading more") {
    of("B77W").factor should be < of("B788").factor
    of("B744").factor should be < 0.8
    of("CL35").factor should be > 1.5
    of("C172").factor should be > 1.5
    AircraftResponse.specs.keys.foreach(t => of(t).factor should (be >= 0.5 and be <= 2.5))
  }

  test("types without wing-loading data fall back to their size class") {
    val unknown = AircraftResponse.fromAdsb(Some("A5"), Some("XXXX"))
    unknown.basis shouldBe "class"
    unknown.typeCode shouldBe Some("XXXX")
    unknown.factor shouldBe 0.8
    AircraftResponse.fromAdsb(None, None) shouldBe AircraftResponse.medium
  }

  test("the factor scales the EDR, not the thresholds the categories were calibrated on") {
    Thresholds.classify(0.13, of("A320")) shouldBe TurbulenceCategory.Light
    Thresholds.classify(0.13, of("B744")) shouldBe TurbulenceCategory.Smooth
    Thresholds.classify(0.13, of("CL35")) shouldBe TurbulenceCategory.Moderate
    Thresholds.classify(0.13) shouldBe TurbulenceCategory.Light
    Thresholds.byClass(AircraftClass.Medium) shouldBe Thresholds.reference
  }
}
