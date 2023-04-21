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
            display_id,
            airport_type,
            airport_name,
            latitude_deg,
            longitude_deg,
            iso_country,
            icao_code,
            iata_code,
            local_code,
            keywords
          ) VALUES (
            ${airport.displayId},
            ${airport.airportType},
            ${airport.name},
            ${airport.latitudeDeg},
            ${airport.longitudeDeg},
            ${airport.isoCountry},
            ${airport.icaoCode},
            ${airport.iataCode},
            ${airport.localCode},
            ${airport.keywords}
          )
         """.updateWithLogger.run.map(_ => ())
  }

  override def getAirport(
    airportCode: ICAOCode
  )(using CanReadDB.type): ConnectionIO[Option[AirportRow]] = {
    sql"""
      select display_id,
             airport_type,
             airport_name,
             latitude_deg,
             longitude_deg,
             iso_country,
             icao_code,
             iata_code,
             local_code,
      from airports
      where icao_code = ${airportCode.toString}
    """.queryWithLogger[AirportRow].option
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      createTable <- sql"""
              create table if not exists airports (
                display_id varchar(10) not null,
                airport_type varchar(20) not null,
                airport_name varchar(255) not null,
                latitude_deg double precision not null,
                longitude_deg double precision not null,
                iso_country varchar(2),
                icao_code varchar(10) not null primary key,
                iata_code varchar(3) not null,
                local_code varchar(10),
                keywords varchar(255) array
              )
            """.update.run
      _ <- sql"create index if not exists airports_icao_code_idx on airports (icao_code)".update.run
      _ <- sql"create index if not exists airports_iata_code_idx on airports (iata_code)".update.run
    } yield ()
  }
}
