/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

final class AirportIataIcaoUpdater {
  // https://tgftp.nws.noaa.gov/SL.us008001/DC.avspt/DS.gtggb/PT.grid_DF.gr2/
  // "id","ident","type","name","latitude_deg","longitude_deg","elevation_ft","continent","iso_country","iso_region","municipality","scheduled_service","gps_code","iata_code","local_code","home_link","wikipedia_link","keywords"
  // gps_code = ICAO
  val url = "https://davidmegginson.github.io/ourairports-data/airports.csv"
}
