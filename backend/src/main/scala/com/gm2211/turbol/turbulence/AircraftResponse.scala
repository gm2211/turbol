/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import scala.math.{rint, sqrt, tan, toRadians, Pi}

/**
 * Typical figures for one ICAO type: maximum take-off mass, wing reference area, span, quarter-chord sweep and
 * cruise true airspeed, from manufacturers' published data (approximate: variants differ by a few percent).
 */
final case class AircraftSpec(mtowKg: Double, wingAreaM2: Double, spanM: Double, sweepDeg: Double, cruiseKts: Double) {

  /** Mid-cruise weight over wing area: aircraft cruise at roughly 85% of MTOW on average. */
  def wingLoadingKgM2: Double = AircraftResponse.cruiseWeightFraction * mtowKg / wingAreaM2
}

/**
 * How strongly an aircraft feels a given EDR, relative to the reference aircraft (an A320, for which the categories'
 * EDR thresholds were calibrated): turbulence is classified as if the EDR were `edr * factor`.
 *
 * @param typeCode the ICAO type designator, when ADS-B reports one
 * @param wingLoadingKgM2 the type's mid-cruise wing loading, when the type is in the table
 * @param basis "type" when the factor comes from the type's wing loading, "class" when only its size class is known,
 *              "assumed" when the aircraft is unknown and its class was guessed from the route length
 */
final case class AircraftResponse(
  typeCode: Option[String],
  aircraftClass: AircraftClass,
  wingLoadingKgM2: Option[Double],
  factor: Double,
  basis: String
)

object AircraftResponse {
  val cruiseWeightFraction = 0.85
  private val g = 9.81
  /** Standard-atmosphere density (kg/m^3) and speed of sound (kt) at FL350, where the factor is evaluated. */
  private val rho = 0.38
  private val speedOfSoundKts = 573.6
  /** Factors are clamped to this range: beyond it the gust-load model says more about the table than the ride. */
  private val minFactor = 0.5
  private val maxFactor = 2.5

  /**
   * When the type isn't in the table: the ratios the previous per-class thresholds used (GTGN User Guide values for
   * light and heavy aircraft against medium), so unknown types are classified as before.
   */
  val classFactor: Map[AircraftClass, Double] =
    Map(AircraftClass.Light -> 1.25, AircraftClass.Medium -> 1.0, AircraftClass.Heavy -> 0.8)

  // format: off
  /** MTOW kg, wing area m^2, span m, quarter-chord sweep deg, cruise TAS kt. */
  val specs: Map[String, AircraftSpec] = Map(
    // Airbus narrowbodies
    "A318" -> AircraftSpec(68000, 122.6, 34.1, 25, 450),
    "A319" -> AircraftSpec(75500, 122.6, 34.1, 25, 450),
    "A320" -> AircraftSpec(78000, 122.6, 35.8, 25, 450),
    "A321" -> AircraftSpec(93500, 122.6, 35.8, 25, 450),
    "A19N" -> AircraftSpec(75500, 122.6, 35.8, 25, 450),
    "A20N" -> AircraftSpec(79000, 122.6, 35.8, 25, 450),
    "A21N" -> AircraftSpec(97000, 122.6, 35.8, 25, 450),
    "BCS1" -> AircraftSpec(63100, 112.3, 35.1, 25, 447),
    "BCS3" -> AircraftSpec(70900, 112.3, 35.1, 25, 447),
    // Boeing narrowbodies
    "B712" -> AircraftSpec(54900, 92.97, 28.4, 24.5, 438),
    "B737" -> AircraftSpec(70080, 124.6, 35.8, 25, 450),
    "B738" -> AircraftSpec(79016, 124.6, 35.8, 25, 450),
    "B739" -> AircraftSpec(85139, 124.6, 35.8, 25, 450),
    "B37M" -> AircraftSpec(80286, 127.0, 35.9, 25, 453),
    "B38M" -> AircraftSpec(82191, 127.0, 35.9, 25, 453),
    "B39M" -> AircraftSpec(88314, 127.0, 35.9, 25, 453),
    "B752" -> AircraftSpec(115680, 185.25, 38.05, 25, 459),
    "B753" -> AircraftSpec(123600, 185.25, 38.05, 25, 459),
    // Widebodies
    "A306" -> AircraftSpec(171700, 260.0, 44.84, 28, 470),
    "A332" -> AircraftSpec(242000, 361.6, 60.3, 30, 470),
    "A333" -> AircraftSpec(242000, 361.6, 60.3, 30, 470),
    "A338" -> AircraftSpec(251000, 370.0, 64.0, 30, 470),
    "A339" -> AircraftSpec(251000, 370.0, 64.0, 30, 470),
    "A343" -> AircraftSpec(276500, 361.6, 60.3, 30, 470),
    "A346" -> AircraftSpec(380000, 439.4, 63.45, 31.1, 480),
    "A359" -> AircraftSpec(283000, 442.0, 64.75, 31.9, 488),
    "A35K" -> AircraftSpec(319000, 464.0, 64.75, 31.9, 488),
    "A388" -> AircraftSpec(575000, 845.0, 79.75, 33.5, 488),
    "B763" -> AircraftSpec(186880, 283.3, 47.57, 31.5, 459),
    "B764" -> AircraftSpec(204120, 290.7, 51.9, 31.5, 459),
    // ICAO B772 covers the 777-200 and the far more common 777-200ER: use the ER's MTOW.
    "B772" -> AircraftSpec(297550, 427.8, 60.9, 31.6, 482),
    "B77L" -> AircraftSpec(347800, 436.8, 64.8, 31.6, 490),
    "B77W" -> AircraftSpec(351500, 436.8, 64.8, 31.6, 490),
    "B788" -> AircraftSpec(227930, 360.5, 60.1, 32.2, 488),
    "B789" -> AircraftSpec(254011, 360.5, 60.1, 32.2, 488),
    "B78X" -> AircraftSpec(254011, 360.5, 60.1, 32.2, 488),
    "B744" -> AircraftSpec(396890, 525.0, 64.4, 37.5, 493),
    "B748" -> AircraftSpec(447700, 554.0, 68.4, 37.5, 493),
    "MD11" -> AircraftSpec(286000, 338.9, 51.97, 35, 480),
    // Regional jets and turboprops
    "CRJ2" -> AircraftSpec(24040, 48.35, 21.21, 26, 424),
    "CRJ7" -> AircraftSpec(34019, 70.6, 23.2, 26, 447),
    "CRJ9" -> AircraftSpec(38330, 70.6, 24.9, 26, 447),
    "E170" -> AircraftSpec(37200, 72.7, 26.0, 23.5, 430),
    "E75L" -> AircraftSpec(40370, 72.7, 26.0, 23.5, 430),
    "E75S" -> AircraftSpec(40370, 72.7, 26.0, 23.5, 430),
    "E190" -> AircraftSpec(51800, 92.5, 28.7, 23.5, 447),
    "E195" -> AircraftSpec(52290, 92.5, 28.7, 23.5, 447),
    "E290" -> AircraftSpec(56400, 103.0, 33.7, 23.5, 450),
    "E295" -> AircraftSpec(61500, 103.0, 35.1, 23.5, 450),
    "AT45" -> AircraftSpec(18600, 54.5, 24.57, 0, 260),
    "AT75" -> AircraftSpec(22800, 61.0, 27.05, 0, 275),
    "AT76" -> AircraftSpec(23000, 61.0, 27.05, 0, 275),
    "DH8D" -> AircraftSpec(29574, 63.08, 28.42, 0, 360),
    // Business and general aviation
    "GLF6" -> AircraftSpec(45178, 119.2, 30.36, 36, 488),
    "GLEX" -> AircraftSpec(45132, 94.95, 28.65, 35, 488),
    "CL35" -> AircraftSpec(18416, 48.3, 21.0, 27, 470),
    "C68A" -> AircraftSpec(13970, 50.4, 22.05, 16, 446),
    "PC12" -> AircraftSpec(4740, 25.8, 16.28, 0, 280),
    "C208" -> AircraftSpec(3995, 25.96, 15.88, 0, 186),
    "SR22" -> AircraftSpec(1633, 13.5, 11.68, 0, 180),
    "C172" -> AircraftSpec(1111, 16.2, 11.0, 0, 122)
  )
  // format: on

  /**
   * Peak vertical gust load per unit gust velocity, from Pratt's gust load formula (NACA Report 1206, as in
   * 14 CFR 25.341 before 1996 and CS-23.341): dn = rho * V * a * Kg * U / (2 * W/S), with the gust alleviation
   * factor Kg = 0.88 mu / (5.3 + mu), mass ratio mu = 2 (W/S) / (rho * c * a * g), mean chord c = S / b, and the
   * lift-curve slope a from the DATCOM formula for a swept wing at the cruise Mach number.
   */
  def gustLoadPerUnitGust(s: AircraftSpec): Double = {
    val wingLoadingNM2 = cruiseWeightFraction * s.mtowKg * g / s.wingAreaM2
    val aspectRatio = s.spanM * s.spanM / s.wingAreaM2
    val mach = s.cruiseKts / speedOfSoundKts
    val beta = sqrt(1 - mach * mach)
    val tanSweep = tan(toRadians(s.sweepDeg))
    val k = 0.95
    val a = 2 * Pi * aspectRatio /
      (2 + sqrt(4 + (aspectRatio * beta / k) * (aspectRatio * beta / k) * (1 + tanSweep * tanSweep / (beta * beta))))
    val chord = s.wingAreaM2 / s.spanM
    val mu = 2 * wingLoadingNM2 / (rho * chord * a * g)
    val kg = 0.88 * mu / (5.3 + mu)
    rho * s.cruiseKts * 0.514444 * a * kg / (2 * wingLoadingNM2)
  }

  private val reference = gustLoadPerUnitGust(specs("A320"))

  /** Response factor for a type in the table: its gust load against the A320's, rounded to two decimals. */
  def typeFactor(s: AircraftSpec): Double =
    rint(math.max(minFactor, math.min(maxFactor, gustLoadPerUnitGust(s) / reference)) * 100) / 100

  def forClass(c: AircraftClass): AircraftResponse = AircraftResponse(None, c, None, classFactor(c), "class")

  /** The response for an ADS-B report: the type's wing loading when it's in the table, else its size class. */
  def fromAdsb(category: Option[String], typeCode: Option[String]): AircraftResponse = {
    val aircraftClass = AircraftClass.fromAdsb(category, typeCode)
    val code = typeCode.map(_.trim.toUpperCase).filter(_.nonEmpty)
    code.flatMap(specs.get) match {
      case Some(s) => AircraftResponse(code, aircraftClass, Some(rint(s.wingLoadingKgM2)), typeFactor(s), "type")
      case None => forClass(aircraftClass).copy(typeCode = code)
    }
  }

  val medium: AircraftResponse = forClass(AircraftClass.Medium)

  /** Routes at least this long are flown by widebodies (heavy aircraft); shorter ones mostly by narrowbodies. */
  val longHaulKm = 4500.0

  /** When the aircraft is unknown: the class typical for a route this long, marked as assumed. */
  def assumedFor(distanceKm: Double): AircraftResponse =
    forClass(if (distanceKm >= longHaulKm) AircraftClass.Heavy else AircraftClass.Medium).copy(basis = "assumed")
}
