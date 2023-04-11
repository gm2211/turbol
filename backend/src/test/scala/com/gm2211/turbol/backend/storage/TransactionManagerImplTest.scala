package com.gm2211.turbol.backend.storage

import cats.effect.unsafe.IORuntime
import cats.effect.{Deferred, IO}
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.backend.config.runtime.DatabaseConfig
import com.gm2211.turbol.backend.config.secrets.AppSecrets
import com.gm2211.turbol.backend.objects.internal.model.airports.ICAOCode
import com.gm2211.turbol.backend.objects.internal.storage.capabilities.CanReadDB
import com.gm2211.turbol.backend.objects.internal.storage.db.TransactionalStores
import com.gm2211.turbol.backend.storage.stores.AirportsStoreImpl
import com.gm2211.turbol.backend.util.MoreExecutors.*
import com.gm2211.turbol.backend.util.{CatsUtils, MoreExecutors}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext

class TransactionManagerImplTest extends AnyFunSuite with CatsUtils {
  private val dbTransactorProviderImpl = new DbTransactorProviderImpl(
    Refreshable(DatabaseConfig("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "")), Refreshable(AppSecrets(""))
  )(using MoreExecutors.io("test-io").toExecutionContext)
  private val fixture = new TransactionManagerImpl(TransactionalStores(AirportsStoreImpl()), dbTransactorProviderImpl)


  test("read write") {
    dbTransactorProviderImpl.waitUntilInitialized()
    fixture.readOnly { txn =>
      txn.airportsStore.getAirport(ICAOCode("test"))
    }.unsafeRunSync()
  }
}
