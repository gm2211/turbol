/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.objects.internal.storage.db

import com.gm2211.turbol.objects.internal.storage.RawSqlStore
import com.gm2211.turbol.storage.stores.{AirportsStore, AppMetadataStore, DBStore}

case class TransactionalStores(
  airportsStore: AirportsStore,
  appMetadataStore: AppMetadataStore,
  raw: RawSqlStore
) extends Iterable[DBStore] {
  override def iterator: Iterator[DBStore] = {
    this.productIterator.collect[DBStore] { case store: DBStore => store }
  }
}
