package com.gm2211.turbol.util

import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.backend.config.runtime.{DatabaseConfig, H2}
import com.gm2211.turbol.backend.config.secrets.AppSecrets
import com.gm2211.turbol.backend.objects.internal.storage.RawSqlStoreImpl
import com.gm2211.turbol.backend.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.backend.storage.stores.AirportsStoreImpl
import com.gm2211.turbol.backend.storage.{DbTransactorProviderImpl, TransactionManagerImpl}
import com.gm2211.turbol.backend.util.MoreExecutors
import com.gm2211.turbol.backend.util.MoreExecutors.*
import doobie.implicits.toSqlInterpolator

trait TestTxnManager {
  private val user = "admin"
  private val password = "password"
  private val dbTransactorProviderImpl = new DbTransactorProviderImpl(
    Refreshable(
      DatabaseConfig(adminUser = user, databaseName = "test-h2", databaseType = H2, hostname = "N/A", port = 5432)
    ),
    Refreshable(AppSecrets(password))
  )(
    using MoreExecutors.io("transactor-provider-io").toExecutionContext
  )
  private val stores: TransactionalStores = TransactionalStores(AirportsStoreImpl(), RawSqlStoreImpl())

  val txnManager = new TransactionManagerImpl(stores, dbTransactorProviderImpl)

  txnManager.awaitInitialized()
  txnManager.readWriteVoid{txn => txn.raw.executeQuery(sql"drop table if exists airports;")}
  txnManager.readWriteVoid{txn => txn.airportsStore.createTableIfNotExists().map(_ => ())}
}
