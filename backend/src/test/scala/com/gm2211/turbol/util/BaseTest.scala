package com.gm2211.turbol.util

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

trait BaseTest extends AnyFunSuite with BeforeAndAfterEach with CatsUtils with MyTryValues with Matchers
trait TestWithDb extends BaseTest with TestTxnManager {
  override def beforeEach(): Unit = {
    super.beforeEach()
    txnManager.readWriteVoid { txn =>
      txn.raw.executeQuery("DROP ALL OBJECTS DELETE FILES")
    }
    txnManager.awaitInitialized()
  }
}
