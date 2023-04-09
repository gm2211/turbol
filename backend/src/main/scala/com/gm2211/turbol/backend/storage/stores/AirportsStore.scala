package com.gm2211.turbol.backend.storage.stores

import cats.*
import cats.data.*
import cats.effect.*
import cats.implicits.*
import com.gm2211.turbol.backend.objects.internal.model.airports.{Airport, ICAOCode}
import com.gm2211.turbol.backend.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.backend.objects.internal.storage.capabilities.CanReadDB
import doobie.*
import doobie.hikari.HikariTransactor
import doobie.implicits.*

trait AirportsStore extends DBStore {
  def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]]
}
final class AirportsStoreImpl extends AirportsStore {
  override def getAirport(airportCode: ICAOCode)(using CanReadDB.type): ConnectionIO[Option[AirportRow]] = {
    sql"""
      SELECT id,
             display_id,
             type,
             name,
             latitude_deg,
             longitude_deg,
             iso_country,
             icao_code,
             iata_code,
             local_code
      FROM airports
      WHERE icao_code = ${airportCode.toString}
    """.query[AirportRow].option
  }

  override def createTableIfNotExists(): ConnectionIO[Unit] = {
    sql"""
      CREATE TABLE IF NOT EXISTS airports (
        id INTEGER PRIMARY KEY,
        display_id VARCHAR(10) NOT NULL,
        type VARCHAR(20) NOT NULL,
        name VARCHAR(255) NOT NULL,
        latitude_deg DECIMAL(9, 6) NOT NULL,
        longitude_deg DECIMAL(9, 6) NOT NULL,
        iso_country VARCHAR(2),
        icao_code VARCHAR(10),
        iata_code VARCHAR(3),
        local_code VARCHAR(10),
        keywords VARCHAR(255)[]
      )
    """.update.run.map(_ => ())
  }
}
