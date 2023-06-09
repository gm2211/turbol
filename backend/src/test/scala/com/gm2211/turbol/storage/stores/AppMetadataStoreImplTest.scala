package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.objects.internal.DatetimeUtc
import com.gm2211.turbol.util.TestWithDb

class AppMetadataStoreImplTest extends TestWithDb {

  test("should be able to store and retrieve last updated time for airports table") {
    stubTimeService.set(DatetimeUtc(1337))
    val lastUpdated = txnManager.readWrite { txn =>
      for {
        _ <- txn.appMetadataStore.markAirportsTableUpdated()
        lstUpdate <- txn.appMetadataStore.getAirportsTableLastUpdated()
      } yield lstUpdate
    }.value
    lastUpdated shouldEqual (DatetimeUtc(1337))
  }

  test("should be able to update and retrieve last updated time for airports table") {
    stubTimeService.set(DatetimeUtc(1337))
    txnManager.readWrite { txn => txn.appMetadataStore.markAirportsTableUpdated() }

    stubTimeService.set(DatetimeUtc(2345))

    val lastUpdated = txnManager.readWrite { txn =>
      for {
        _ <- txn.appMetadataStore.markAirportsTableUpdated()
        lstUpdate <- txn.appMetadataStore.getAirportsTableLastUpdated()
      } yield lstUpdate
    }.value

    lastUpdated shouldEqual (DatetimeUtc(2345))
  }
}
