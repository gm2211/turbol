/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.turbulence

import io.circe.{Decoder, Encoder}

enum TurbulenceCategory(val rank: Int) {
  case NoData extends TurbulenceCategory(-1)
  case Smooth extends TurbulenceCategory(0)
  case Light extends TurbulenceCategory(1)
  case Moderate extends TurbulenceCategory(2)
  case Severe extends TurbulenceCategory(3)
  case Extreme extends TurbulenceCategory(4)
}

object TurbulenceCategory {
  given Encoder[TurbulenceCategory] = Encoder.encodeString.contramap(_.toString)
  val ordered: Seq[TurbulenceCategory] = Seq(Smooth, Light, Moderate, Severe, Extreme, NoData)
}

/** Aircraft weight class: heavier aircraft feel the same EDR less, so thresholds scale with it. */
enum AircraftClass {
  case Light, Medium, Heavy
}

object AircraftClass {
  given Encoder[AircraftClass] = Encoder.encodeString.contramap(_.toString)
  given Decoder[AircraftClass] = Decoder.decodeString.emap { s =>
    AircraftClass.values.find(_.toString.equalsIgnoreCase(s)).toRight(s"unknown aircraft class $s")
  }

  private val heavyTypes = Set("B74", "B77", "B78", "B76", "A33", "A34", "A35", "A38", "MD1", "IL9", "A30")

  /** ICAO type designators of aircraft under ~34 t (ADS-B categories A1/A2): pistons, turboprops, business jets. */
  private val lightTypes = Set(
    "C150", "C152", "C172", "C182", "C206", "C208", "C210", "C310", "C340", "C414", "C421", // Cessna props
    "C25A", "C25B", "C25C", "C25M", "C500", "C501", "C510", "C525", "C550", "C551", "C560", "C56X", "C650", "C680",
    "C68A", "C700", "C750", // Citations
    "P28A", "P28B", "P28R", "PA31", "PA32", "PA34", "PA44", "PA46", "SR20", "SR22", "DA40", "DA42", "DA62", "M20P",
    "M20T", "TBM7", "TBM8", "TBM9", "PC12", "PC24", "DHC6", "BE20", "BE35", "BE36", "BE40", "BE58", "BE9L", "B350",
    "LJ35", "LJ45", "LJ60", "LJ75", "E50P", "E55P", "E545", "E550", "CL30", "CL35", "CL60", "F2TH", "FA50", "FA7X",
    "FA8X", "GLF4", "H25B"
  )

  /** Rough mapping from ICAO wake category / type designator, as reported by ADS-B aggregators. */
  def fromAdsb(category: Option[String], typeCode: Option[String]): AircraftClass = {
    (category, typeCode.map(_.toUpperCase)) match {
      case (Some("A5"), _) => Heavy
      case (_, Some(t)) if heavyTypes.exists(t.startsWith) => Heavy
      case (Some("A1") | Some("A2"), _) => Light
      case (_, Some(t)) if lightTypes.contains(t) => Light
      case _ => Medium
    }
  }
}

final case class Thresholds(light: Double, moderate: Double, severe: Double, extreme: Double)

object Thresholds {
  // Medium: Light/Moderate recalibrated against PIREPs for the 3 km grid (data-pipeline/calibrate.py, PR #18);
  // Severe/Extreme from the GTGN User Guide (Fig. 2). Light/Heavy aircraft use the guide's values scaled by the
  // same calibration ratios (0.8 for Light, 0.7 for Moderate) until enough PIREPs exist to fit them directly.
  val byClass: Map[AircraftClass, Thresholds] = Map(
    AircraftClass.Light -> Thresholds(0.13 * 0.8, 0.16 * 0.7, 0.36, 0.64),
    AircraftClass.Medium -> Thresholds(0.12, 0.14, 0.44, 0.79),
    AircraftClass.Heavy -> Thresholds(0.17 * 0.8, 0.24 * 0.7, 0.54, 0.96)
  )

  def classify(edr: Double, aircraftClass: AircraftClass = AircraftClass.Medium): TurbulenceCategory = {
    val t = byClass(aircraftClass)
    if (edr.isNaN) TurbulenceCategory.NoData
    else if (edr < t.light) TurbulenceCategory.Smooth
    else if (edr < t.moderate) TurbulenceCategory.Light
    else if (edr < t.severe) TurbulenceCategory.Moderate
    else if (edr < t.extreme) TurbulenceCategory.Severe
    else TurbulenceCategory.Extreme
  }
}
