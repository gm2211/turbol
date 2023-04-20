/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.api.flights

import com.gm2211.turbol.objects.api.location.GPSLocation

case class FlightPlan(flightNumber: FlightNumber, waypoints: List[GPSLocation])
