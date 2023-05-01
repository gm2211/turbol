package com.gm2211.turbol.background

import com.gm2211.turbol.background.airportdata.{AirportDataDownloader, AirportDataParserFactoryImpl, AirportDataUpdater}
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.{StubTimeService, TestWithDb}

import java.io.File
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
    AirportDataUpdater(
      txnManager,
      AirportDataParserFactoryImpl(),
      mockDownloader,
      StubTimeService()
    ).fetchAndUpdateAirportData().assertSuccess
    println(txnManager.readOnly { txn => txn.raw.executeQueryList[AirportRow]("select * from airports") })
  }
}
