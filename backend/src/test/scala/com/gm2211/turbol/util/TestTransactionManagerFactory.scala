package com.gm2211.turbol.util

import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.runtime.{DatabaseConfig, Postgres}
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.objects.internal.storage.RawSqlStoreImpl
import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.storage.*
import com.gm2211.turbol.storage.stores.AirportsStoreImpl
import com.softwaremill.tagging.*

import scala.util.Try

class TestTransactionManagerFactory extends StringUtils with ConfigSerialization {
  private val user: String = "postgres"
  private val appSecrets: Try[AppSecrets] = "./backend/var/conf/secrets.yml".asPath.toFile.readAsYaml[AppSecrets]
  private val dbTransactorProviderImpl: DBTransactorProviderImpl = new DBTransactorProviderImpl(
    Refreshable(
      DatabaseConfig(
        adminUser = user,
        databaseName = "dev",
        databaseType = Postgres,
        hostname = "postgresql",
        port = 5432
      )
    ),
    Refreshable(appSecrets.get),
    MoreExecutors.io("transactor-provider-io").taggedWith[DBTransactorProvider]
  )
  private val stores: TransactionalStores = TransactionalStores(AirportsStoreImpl(), RawSqlStoreImpl())

  val txnManager = new TransactionManagerImpl(stores, dbTransactorProviderImpl)
}
