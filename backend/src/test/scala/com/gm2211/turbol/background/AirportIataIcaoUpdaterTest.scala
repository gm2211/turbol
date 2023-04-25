package com.gm2211.turbol.background

import com.gm2211.turbol.background.airportdata.{AirportDataDownloader, AirportDataParserFactory, AirportDataUpdater}
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.TestWithDb

import java.io.File
import scala.io.Source
import scala.util.Try

class AirportIataIcaoUpdaterTest extends TestWithDb {
  val mockDownloader = new AirportDataDownloader {
    import com.gm2211.turbol.util.FileUtils.*
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

  test("") {
    new AirportDataUpdater(
      txnManager,
      AirportDataParserFactory,
      mockDownloader
    ).fetchAndUpdateAirportData().assertSuccess
    println(txnManager.readOnly { txn => txn.raw.executeQueryList[AirportRow]("select * from airports") })
  }
}
