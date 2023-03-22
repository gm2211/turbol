/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.objects.flights

import io.circe.{Encoder, Json}

opaque type FlightNumber <: String = String
object FlightNumber {
  def apply(value: String): FlightNumber = value
  given Encoder[FlightNumber]            = (a: FlightNumber) => Json.fromString(a)
}
