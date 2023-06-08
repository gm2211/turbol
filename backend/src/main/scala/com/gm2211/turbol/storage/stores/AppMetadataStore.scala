package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.objects.internal.DatetimeUtc
import com.gm2211.turbol.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB}
import com.gm2211.turbol.services.TimeService
import com.gm2211.turbol.util.{BackendSerialization, SqlCol, SqlTable}
import doobie.*

trait AppMetadataStore extends DBStore {
  def airportsTableUpdated()(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit]
  def getAirportsTableLastUpdated()(using CanReadDB.type): ConnectionIO[DatetimeUtc]
}
final class AppMetadataStoreImpl(timeService: TimeService) extends AppMetadataStore with BackendSerialization {
  import AppMetadataStore.*

  override def airportsTableUpdated()(using CanReadDB.type, CanWriteToDB.type): ConnectionIO[Unit] = {
    doob"""
    insert into $tableName ($keyCol, $valueCol)
    values ($lastUpdatedAirportsTableKey, ${timeService.now.epochMillis})
    """.updateWithLogger.run.map(_ => ())
  }

  override def getAirportsTableLastUpdated()(using CanReadDB.type): doobie.ConnectionIO[DatetimeUtc] = {
    doob"""
    select $valueCol
    from $tableName
    where $keyCol = $lastUpdatedAirportsTableKey
    """.queryWithLogger[Long].unique.map(DatetimeUtc(_))
  }

  override def createTableIfNotExists(): ConnectionIO[Any] = {
    for {
      _ <-
        doob"""
          create table if not exists $tableName (
            $keyCol text not null primary key,
            $valueCol bigint
          )
        """.updateWithLogger.run
      _ <- doob"create index if not exists app_meta_pk_idx on $tableName ($keyCol)".update.run
    } yield ()
  }
}

object AppMetadataStore {
  private[stores] val tableName = SqlTable("app_meta")
  private[stores] val keyCol = SqlCol("key")
  private[stores] val valueCol = SqlCol("value")

  private[stores] val lastUpdatedAirportsTableKey = "last_updated_airports_table"
}
