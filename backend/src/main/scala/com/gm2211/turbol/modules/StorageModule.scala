/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.modules

import com.gm2211.turbol.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.objects.internal.storage.{RawSqlStore, RawSqlStoreImpl}
import com.gm2211.turbol.storage.*
import com.gm2211.turbol.storage.stores.{AirportsStore, AirportsStoreImpl}
import com.gm2211.turbol.util.{MoreExecutors, Scheduler}
import com.softwaremill.macwire.{Module, wire}
import com.softwaremill.tagging.*

import scala.annotation.unused

@Module
class StorageModule(@unused configModule: ConfigModule) {
  @unused private lazy val transactorProviderExecutor: Scheduler @@ DBTransactorProvider =
    MoreExecutors.io("db-transactor-provider").taggedWith[DBTransactorProvider]

  // Stores
  @unused lazy val stores: TransactionalStores = wire[TransactionalStores]
  @unused lazy val airportsStore: AirportsStore = wire[AirportsStoreImpl]
  @unused lazy val rawSqlStore: RawSqlStore = wire[RawSqlStoreImpl]

  // Transactor
  @unused lazy val transactorProvider: DBTransactorProvider = wire[DBTransactorProviderImpl]

  // Transaction manager
  @unused lazy val txnManager: TransactionManager = wire[TransactionManagerImpl]
}
