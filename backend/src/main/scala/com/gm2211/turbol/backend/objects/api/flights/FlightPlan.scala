/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.backend.objects.api.flights

import com.gm2211.turbol.backend.objects.api.location.GPSLocation

case class FlightPlan(flightNumber: FlightNumber, waypoints: List[GPSLocation])
