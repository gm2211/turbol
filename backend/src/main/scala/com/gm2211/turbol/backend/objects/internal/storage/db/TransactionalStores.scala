package com.gm2211.turbol.backend.objects.internal.storage.db

import com.gm2211.turbol.backend.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB, TxnCapability}
import com.gm2211.turbol.backend.storage.stores.AirportsStore

case class TransactionalStores(airportsStore: AirportsStore, private val capabilities: Set[TxnCapability]) {
  private object CapabilityGivens {
    capabilities.foreach {
      case CanReadDB => given CanReadDB.type = CanReadDB
      case CanWriteToDB => given CanWriteToDB.type = CanWriteToDB
    }
  }

  import CapabilityGivens.*
}
