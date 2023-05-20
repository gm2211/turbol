package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB}
import doobie.*
import doobie.implicits.*

trait AppMetadataStore extends DBStore {
  def recordFullyUpdatedAirportsTable()(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit]
}
final class AppMetadataStoreImpl extends AppMetadataStore {
  override def recordFullyUpdatedAirportsTable()(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit] = {
    null
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      _ <-
        sql"""
          create table if not exists airports (
            key varchar(255) not null primary key,
            value text
          )
        """.update.run
      _ <- sql"create index if not exists airports_icao_code_idx on airports (icao_code)".update.run
      _ <- sql"create index if not exists airports_iata_code_idx on airports (iata_code)".update.run
    } yield ()
  }
}
