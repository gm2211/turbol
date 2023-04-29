package com.gm2211.turbol.util

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.storage.TransactionManager
import doobie.implicits.toSqlInterpolator
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.{Failure, Try}

trait BaseTest
    extends AnyFunSuite
    with BeforeAndAfterEach
    with CatsUtils
    with FileUtils
    with StringUtils
    with ExpressionUtils
    with MyTryValues
    with BackendLogging
    with Matchers

trait TestWithDb extends BaseTest {
  private var _txnManager: TransactionManager = null

  override def beforeEach(): Unit = {
    super.beforeEach()

    Try {
      val testTxnManagerFactory = TestTransactionManagerFactory()

      this._txnManager = testTxnManagerFactory.txnManager

      txnManager.awaitInitialized().value

      txnManager.readWriteVoid { txn =>
        txn.raw.executeUpdate(
          sql"""DROP SCHEMA public CASCADE;
            |CREATE SCHEMA public;
            |GRANT ALL ON SCHEMA public TO postgres;
            |GRANT ALL ON SCHEMA public TO public;
            |""".stripMargin
        ).just
      }.value

      txnManager.awaitInitialized().value
    }.recoverWith { error =>
      log.error("Could not initialize test txn manager", error)
      Failure(error)
    }.ignoreRetValue()
  }

  def txnManager: TransactionManager = {
    _txnManager
  }
}
