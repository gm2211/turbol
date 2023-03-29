/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.objects.api.airports

import com.gm2211.turbol.backend.objects.api.location.GPSLocation
import io.circe.{Encoder, Json};

opaque type IATACode <: String = String
opaque type ICAOCode <: String = String

object IATACode:
  def apply(s: String): IATACode = s
  given Encoder[IATACode] = (a: IATACode) => Json.fromString(a)

object ICAOCode:
  def apply(s: String): ICAOCode = s
  given Encoder[ICAOCode] = (a: ICAOCode) => Json.fromString(a)

case class Airport(
  name: String,
  city: String,
  country: String,
  iata: IATACode,
  icao: ICAOCode,
  location: GPSLocation
)
