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
import com.gm2211.turbol.util.{SqlCol, SqlLit, SqlTable}
import doobie.*

trait AirportsStore extends DBStore {
  def putAirport(airport: AirportRow)(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit]

  def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]]

  def search(query: String, limit: Int = 50)(using CanReadDB.type): ConnectionIO[List[AirportRow]]
}

final class AirportsStoreImpl extends AirportsStore {
  import AirportsStore.*

  override def putAirport(airport: AirportRow)(using CanReadDB.type, CanWriteToDB.type): doobie.ConnectionIO[Unit] = {
    val lowerCaseKeywords: List[String] = airport.keywords.map(_.toLowerCase())
    doob"""
          insert into $airportsTable ($allColumns) values (
            ${airport.icaoCode},
            ${airport.iataCode},
            ${airport.airportName},
            ${airport.airportType},
            ${airport.latitudeDeg},
            ${airport.longitudeDeg},
            ${airport.municipality},
            ${airport.isoCountry},
            ${airport.localCode},
            $lowerCaseKeywords
          ) on conflict ($icaoCodeCol) do update
            set $iataCodeCol = ${airport.iataCode},
                $airportNameCol = ${airport.airportName},
                $airportTypeCol = ${airport.airportType},
                $latitudeDegCol = ${airport.latitudeDeg},
                $longitudeDegCol = ${airport.longitudeDeg},
                $municipalityCol = ${airport.municipality},
                $isoCountryCol = ${airport.isoCountry},
                $localCodeCol = ${airport.localCode},
                $keywordsCol = $lowerCaseKeywords
         """.updateWithLogger.run.map(_ => ())
  }

  override def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]] = {
    doob"""
      select $allColumns
      from $airportsTable
      where icao_code = ${airportCode}
    """.queryWithLogger[AirportRow].option
  }

  override def search(query: String, limit: Int = 50)(using CanReadDB.type): ConnectionIO[List[AirportRow]] = {
    val likeQuery = s"%${query.toLowerCase}%"
    val keywordQuery = if (query.length > 5) {
      s"%${query.toLowerCase()}%"
    } else {
      s"${query.toLowerCase()}"
    }
    def rankStatement(boost: Int = 1) =
      SqlLit(s"case when ${airportTypeCol.value} = 'large_airport' then ${boost} else ${2 * boost} end as rank")

    doob"""
      (
        select *, ${rankStatement()}
        from $airportsTable
        where $icaoCodeCol = $likeQuery
          or $iataCodeCol = $likeQuery
      )
      union
      (
        select *, ${rankStatement(10)}
        from $airportsTable 
        where $icaoCodeCol ilike $likeQuery
          or $iataCodeCol ilike $likeQuery
      )
      union
      (
        select *, ${rankStatement(100)}
        from $airportsTable
        where $airportNameCol ilike $likeQuery
        or $airportTypeCol ilike $likeQuery
        or $isoCountryCol ilike $likeQuery
        or $localCodeCol ilike $likeQuery
        or $icaoCodeCol in (
          select $icaoCodeCol
          from $airportsTable, unnest($keywordsCol) as keyword
          where keyword ilike $keywordQuery
        )
      )
      order by rank
      limit $limit
    """.queryWithLogger[AirportRow].to[List]
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      _ <- doob"""create table if not exists $airportsTable (
                $icaoCodeCol varchar(10) not null primary key,
                $iataCodeCol varchar(3) not null,
                $airportNameCol varchar(255),
                $airportTypeCol varchar(20),
                $latitudeDegCol double precision,
                $longitudeDegCol double precision,
                $municipalityCol varchar(255),
                $isoCountryCol varchar(2),
                $localCodeCol varchar(10),
                $keywordsCol varchar(255) array
              )
            """.update.run
      _ <- doob"create index if not exists airports_icao_code_idx on $airportsTable ($icaoCodeCol)".update.run
      _ <- doob"create index if not exists airports_iata_code_idx on $airportsTable ($iataCodeCol)".update.run
    } yield ()
  }
}

object AirportsStore {
  private[stores] val airportsTable = SqlTable("airports")
  private[stores] val icaoCodeCol = SqlCol("icao_code")
  private[stores] val iataCodeCol = SqlCol("iata_code")
  private[stores] val airportNameCol = SqlCol("airport_name")
  private[stores] val airportTypeCol = SqlCol("airport_type")
  private[stores] val latitudeDegCol = SqlCol("latitude_deg")
  private[stores] val longitudeDegCol = SqlCol("longitude_deg")
  private[stores] val municipalityCol = SqlCol("municipality")
  private[stores] val isoCountryCol = SqlCol("iso_country")
  private[stores] val localCodeCol = SqlCol("local_code")
  private[stores] val keywordsCol = SqlCol("keywords")

  private[stores] val allColumns = List(
    icaoCodeCol,
    iataCodeCol,
    airportNameCol,
    airportTypeCol,
    latitudeDegCol,
    longitudeDegCol,
    municipalityCol,
    isoCountryCol,
    localCodeCol,
    keywordsCol
  )
}
