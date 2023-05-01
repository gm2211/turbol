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

  def search(query: String, limit: Int = 50)(using CanReadDB.type): ConnectionIO[List[AirportRow]]
}

final class AirportsStoreImpl extends AirportsStore {
  override def putAirport(airport: AirportRow)(using CanReadDB.type, CanWriteToDB.type): doobie.ConnectionIO[Unit] = {
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

  override def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]] = {
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

  override def search(query: String, limit: Int = 50)(using CanReadDB.type): ConnectionIO[List[AirportRow]] = {
    val likeQuery = s"%${query.toLowerCase}%"
    val keywordQuery = if (query.length > 5) {
      s"%${query.toLowerCase()}%"
    } else {
      s"${query.toLowerCase()}"
    }
    sql"""
      (
        select *, case when airport_type = 'large_airport' then 1 else 2 end as rank
        from airports 
        where icao_code = ${likeQuery}
          or iata_code = ${likeQuery}
      )
      union
      (
        select *, case when airport_type = 'large_airport' then 10 else 20 end as rank
        from airports 
        where icao_code ilike ${likeQuery}
          or iata_code ilike ${likeQuery}
      )
      union
      (
        select *, case when airport_type = 'large_airport' then 100 else 200 end as rank
        from airports
        where airport_name ilike ${likeQuery}
        or airport_type ilike ${likeQuery}
        or iso_country ilike ${likeQuery}
        or local_code ilike ${likeQuery}
        or icao_code in (
          select icao_code
          from airports, unnest(keywords) as keyword
          where keyword ilike ${keywordQuery}
        )
      )
      order by rank
      limit ${limit}
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
