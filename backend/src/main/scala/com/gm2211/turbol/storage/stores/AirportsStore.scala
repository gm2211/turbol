/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.storage.stores

import cats.*
import com.gm2211.turbol.objects.internal.model.airports.ICAOCode
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB}
import doobie.*
import doobie.implicits.*
import doobie.postgres.*
import doobie.postgres.implicits.*

trait AirportsStore extends DBStore {
  def putAirport(
    airport: AirportRow
  )(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit]

  def getAirport(
    airportCode: ICAOCode
  )(using CanReadDB.type): ConnectionIO[Option[AirportRow]]
}

final class AirportsStoreImpl extends AirportsStore {
  override def putAirport(airport: AirportRow)(
    using
    CanReadDB.type,
    CanWriteToDB.type
  ): doobie.ConnectionIO[Unit] = {
    sql"""
          INSERT INTO airports (
            icao_code,
            iata_code,
            airport_name,
            airport_type,
            latitude_deg,
            longitude_deg,
            iso_country,
            local_code,
            keywords
          ) VALUES (
            ${airport.icaoCode},
            ${airport.iataCode},
            ${airport.airportName},
            ${airport.airportType},
            ${airport.latitudeDeg},
            ${airport.longitudeDeg},
            ${airport.isoCountry},
            ${airport.localCode},
            ${airport.keywords}
          )
         """.updateWithLogger.run.map(_ => ())
  }

  override def getAirport(
    airportCode: ICAOCode
  )(using CanReadDB.type): ConnectionIO[Option[AirportRow]] = {
    sql"""
      select 
            icao_code,
            iata_code,
            airport_name,
            airport_type,
            latitude_deg,
            longitude_deg,
            iso_country,
            local_code,
      from airports
      where icao_code = ${airportCode.toString}
    """.queryWithLogger[AirportRow].option
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      createTable <- sql"""
              create table if not exists airports (
                icao_code varchar(10) not null primary key,
                iata_code varchar(3) not null,
                airport_name varchar(255),
                airport_type varchar(20),
                latitude_deg double precision,
                longitude_deg double precision,
                iso_country varchar(2),
                local_code varchar(10),
                keywords varchar(255) array
              )
            """.update.run
      _ <- sql"create index if not exists airports_icao_code_idx on airports (icao_code)".update.run
      _ <- sql"create index if not exists airports_iata_code_idx on airports (iata_code)".update.run
    } yield ()
  }
}
