package com.gm2211.turbol.util

import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.runtime.DatabaseConfig
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.objects.internal.storage.RawSqlStoreImpl
import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.storage.*
import com.gm2211.turbol.storage.stores.{AirportsStoreImpl, AppMetadataStoreImpl}
import com.softwaremill.tagging.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

class TestTransactionManagerFactory() extends StringUtils with ConfigSerialization {
  private val postgres = EmbeddedPostgres.start()
  private val dbTransactorProviderImpl: DBTransactorProviderImpl = new DBTransactorProviderImpl(
    Refreshable(
      DatabaseConfig(
        adminUser = "postgres", // Default for embedded postgres
        databaseName = "postgres", // Default for embedded postgres
        hostname = "localhost", // Default for embedded postgres
        port = postgres.getPort
      )
    ),
    Refreshable(AppSecrets("postgres", "") /* Default for embedded postgres */ ),
    MoreExecutors.io("transactor-provider-io").taggedWith[DBTransactorProvider]
  )
  private val _stubTimeService = new StubTimeService()
  private val stores: TransactionalStores = TransactionalStores(
    AirportsStoreImpl(),
    AppMetadataStoreImpl(_stubTimeService),
    RawSqlStoreImpl()
  )

  val txnManager: TransactionManager = new TransactionManagerImpl(stores, dbTransactorProviderImpl)
  def stubTimeService: StubTimeService = _stubTimeService

  def close(): Unit = {
    postgres.close()
  }
}
