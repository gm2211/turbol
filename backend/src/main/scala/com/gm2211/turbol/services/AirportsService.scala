package com.gm2211.turbol.services

import com.gm2211.turbol.objects.internal.model.airports.Airport
import com.gm2211.turbol.storage.TransactionManager

import scala.util.Try

trait AirportsService {
  def search(query: String): Try[Seq[Airport]]
}

final class AirportsServiceImpl(txnManager: TransactionManager) extends AirportsService {
  override def search(query: String): Try[Seq[Airport]] = {
    txnManager.readOnly { txn => txn.airportsStore.search(query).map(_.map(_.toAirport)) }
  }
}