/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

import com.gm2211.turbol.background.airportdata.{AirportDataDownloader, AirportDataParserFactoryImpl, AirportDataUpdater}
import com.gm2211.turbol.objects.internal.DatetimeUtc
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.DBUtils.doob
import com.gm2211.turbol.util.{SqlCol, TestWithDb}

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import scala.io.Source
import scala.util.Try

class AirportDataUpdaterTest extends TestWithDb {
  private val stubDownloader: StubAirportDataDownloader = new StubAirportDataDownloader

  private val fixture: AirportDataUpdater = AirportDataUpdater(
    txnManager,
    AirportDataParserFactoryImpl(),
    stubDownloader,
    stubTimeService
  )

  override def extraBeforeEach(): Unit = {
    stubDownloader.invoked.set(false)
  }

  test("should correctly parse and store airports data") {
    listAirports() shouldBe empty

    fixture.fetchAndUpdateAirportData().assertSuccess
    stubDownloader.invoked.get() shouldBe true

    listAirports() should not be empty
  }

  test("should mark airports table as updated after successful update") {
    val noLastUpdatedTime = txnManager.readOnly { txn => txn.appMetadataStore.getAirportsTableLastUpdated() }.isFailure

    // noinspection ScalaUnusedExpression -- false positive
    noLastUpdatedTime shouldEqual true

    stubTimeService.set(DatetimeUtc(1337))
    fixture.fetchAndUpdateAirportData().assertSuccess
    stubDownloader.invoked.get() shouldBe true

    val lastUpdated = txnManager.readOnly { txn => txn.appMetadataStore.getAirportsTableLastUpdated() }.value

    lastUpdated should equal(DatetimeUtc(1337))
  }

  test("should not update airports if recently updated") {
    txnManager.readWriteVoid { txn => List(txn.appMetadataStore.markAirportsTableUpdated()) }
    fixture.fetchAndUpdateAirportData().assertSuccess
    stubDownloader.invoked.get() shouldBe false
  }

  private def listAirports(): List[AirportRow] = {
    txnManager.readOnly { txn =>
      txn.raw.executeQueryList[AirportRow](doob"select ${SqlCol("*")} from airports")
    }.success.get
  }
  private class StubAirportDataDownloader extends AirportDataDownloader {
    val invoked: AtomicBoolean = AtomicBoolean(false)

    override def downloadToTempFile: Try[File] = {
      invoked.set(true)
      Try {
        val tmpFile = File.createTempFile("airports", ".csv")
        tmpFile.deleteOnExit()
        val airportsCsv = Source.fromURL(getClass.getClassLoader.getResource("airports.csv"))

        tmpFile.usingStreamWriter { writer =>
          airportsCsv.getLines().foreach { line =>
            writer.write(s"$line\n")
          }
        }
        tmpFile
      }
    }
  }
}
