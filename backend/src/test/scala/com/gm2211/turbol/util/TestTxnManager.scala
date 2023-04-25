package com.gm2211.turbol.util

import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.config.runtime.{DatabaseConfig, H2}
import com.gm2211.turbol.config.secrets.AppSecrets
import com.gm2211.turbol.objects.internal.storage.RawSqlStoreImpl
import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.storage.*
import com.gm2211.turbol.storage.stores.AirportsStoreImpl
import com.gm2211.turbol.util.MoreExecutors.*
import com.softwaremill.tagging.*
import doobie.implicits.toSqlInterpolator

trait TestTxnManager {
  private val user = "admin"
  private val password = "password"
  private val dbTransactorProviderImpl = new DBTransactorProviderImpl(
    Refreshable(
      DatabaseConfig(adminUser = user, databaseName = "test-h2", databaseType = H2, hostname = "N/A", port = 5432)
    ),
    Refreshable(AppSecrets(password)),
    MoreExecutors.io("transactor-provider-io").taggedWith[DBTransactorProvider]
  )
  private val stores: TransactionalStores = TransactionalStores(AirportsStoreImpl(), RawSqlStoreImpl())

  val txnManager = new TransactionManagerImpl(stores, dbTransactorProviderImpl)

  txnManager.awaitInitialized()
  txnManager.readWriteVoid { txn => txn.raw.executeUpdate(sql"drop table if exists airports;") }
  txnManager.readWriteVoid { txn => txn.airportsStore.createTableIfNotExists().map(_ => ()) }
}
