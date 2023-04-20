package com.gm2211.turbol.backend.objects.internal.storage.db

import com.gm2211.turbol.backend.objects.internal.storage.RawSqlStore
import com.gm2211.turbol.backend.objects.internal.storage.capabilities.{CanReadDB, CanWriteToDB, TxnCapability}
import com.gm2211.turbol.backend.storage.stores.{AirportsStore, DBStore}

import scala.collection.IterableFactory

case class TransactionalStores(airportsStore: AirportsStore, raw: RawSqlStore) extends Iterable[DBStore] {
  override def iterator: Iterator[DBStore] = {
    Iterable(airportsStore).iterator
  }
}
