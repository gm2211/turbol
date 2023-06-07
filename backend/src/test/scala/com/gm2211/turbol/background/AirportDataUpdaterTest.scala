package com.gm2211.turbol.background

import com.gm2211.turbol.background.airportdata.{AirportDataDownloader, AirportDataParserFactoryImpl, AirportDataUpdater}
import com.gm2211.turbol.objects.internal.DatetimeUtc
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.DBUtils.doob
import com.gm2211.turbol.util.{SqlCol, StubTimeService, TestWithDb}

import java.io.File
import java.time.Instant
import scala.io.Source
import scala.util.Try

class AirportDataUpdaterTest extends TestWithDb {
  private val mockDownloader = new AirportDataDownloader {
    override def downloadToTempFile: Try[File] = {
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

  test("should correctly parse and store airports data") {
    listAirports() shouldBe empty

    val timeService = StubTimeService().set(DatetimeUtc(Instant.now.toEpochMilli))

    AirportDataUpdater(
      txnManager,
      AirportDataParserFactoryImpl(),
      mockDownloader,
      timeService
    ).fetchAndUpdateAirportData().assertSuccess

    listAirports() should not be empty
  }
  private def listAirports(): List[AirportRow] = {
    txnManager.readOnly { txn =>
      txn.raw.executeQueryList[AirportRow](doob"select ${SqlCol("*")} from airports")
    }.success.get
  }
}
