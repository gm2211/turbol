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
  def putAirport(airport: AirportRow)(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit]

  def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]]

  def search(query: String)(using CanReadDB.type): ConnectionIO[List[AirportRow]]
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
            municipality,
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
            ${airport.municipality},
            ${airport.isoCountry},
            ${airport.localCode},
            ${airport.keywords.map(_.toLowerCase())}
          ) on conflict (icao_code) do update
            set iata_code = ${airport.iataCode},
                airport_name = ${airport.airportName},
                airport_type = ${airport.airportType},
                latitude_deg = ${airport.latitudeDeg},
                longitude_deg = ${airport.longitudeDeg},
                municipality = ${airport.municipality},
                iso_country = ${airport.isoCountry},
                local_code = ${airport.localCode},
                keywords = ${airport.keywords.map(_.toLowerCase())}
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
            municipality,
            iso_country,
            local_code,
            keywords
      from airports
      where icao_code = ${airportCode.toString}
    """.queryWithLogger[AirportRow].option
  }

  override def search(
    query: String
  )(using CanReadDB.type): ConnectionIO[List[AirportRow]] = {
    val keywordQuery = if (query.length > 5) {
      s"%${query.toLowerCase()}%"
    } else {
      s"${query.toLowerCase()}"
    }
    sql"""
      select 
            icao_code,
            iata_code,
            airport_name,
            airport_type,
            latitude_deg,
            longitude_deg,
            municipality,
            iso_country,
            local_code,
            keywords
      from airports
      where icao_code ilike ${"%" + query.toLowerCase + "%"}
      or iata_code ilike ${"%" + query.toLowerCase + "%"}
      or airport_name ilike ${"%" + query.toLowerCase + "%"}
      or airport_type ilike ${"%" + query.toLowerCase + "%"}
      or iso_country ilike ${"%" + query.toLowerCase + "%"}
      or local_code ilike ${"%" + query.toLowerCase + "%"}
      or icao_code in (
        select icao_code
        from airports, unnest(keywords) as keyword
        where keyword ilike ${keywordQuery}
      )
    """.queryWithLogger[AirportRow].to[List]
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      _ <- sql"""
              create table if not exists airports (
                icao_code varchar(10) not null primary key,
                iata_code varchar(3) not null,
                airport_name varchar(255),
                airport_type varchar(20),
                latitude_deg double precision,
                longitude_deg double precision,
                municipality varchar(255),
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
