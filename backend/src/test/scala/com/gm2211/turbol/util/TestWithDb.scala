/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.util

import com.gm2211.turbol.storage.TransactionManager
import com.gm2211.turbol.util.DBUtils.doob

import scala.util.{Failure, Try}

trait TestWithDb extends BaseTest {
  private val testTxnManagerFactory: TestTransactionManagerFactory = TestTransactionManagerFactory()

  override def beforeEach(): Unit = {
    super.beforeEach()

    Try {
      txnManager.awaitInitialized().value

      txnManager.readWriteVoid { txn =>
        txn.raw.executeUpdate(
          doob"""DROP SCHEMA public CASCADE;
               |CREATE SCHEMA public;
               |GRANT ALL ON SCHEMA public TO postgres;
               |GRANT ALL ON SCHEMA public TO public;
               |""".stripMargin
        ).just
      }.value

      txnManager.awaitInitialized().value
    } match {
      case Failure(exception) =>
        log.error("Could not initialize test txn manager", exception)
        throw exception
      case _ => ()
    }
    
    extraBeforeEach()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    testTxnManagerFactory.close()
  }
  
  def extraBeforeEach(): Unit = ()

  def txnManager: TransactionManager = {
    testTxnManagerFactory.txnManager
  }

  def stubTimeService: StubTimeService = {
    testTxnManagerFactory.stubTimeService
  }
}
