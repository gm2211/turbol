/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background.airportdata

import cats.effect.IO
import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.background.BackgroundJob
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.services.TimeService
import com.gm2211.turbol.storage.TransactionManager
import com.gm2211.turbol.util
import com.gm2211.turbol.util.StringUtils

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

final class AirportDataUpdater(
  private val txnManager: TransactionManager,
  private val parserFactory: AirportDataParserFactory,
  private val downloader: AirportDataDownloader,
  private val timeService: TimeService
) extends BackgroundJob with util.FileUtils with StringUtils with BackendLogging {
  private val lastUpdated = AtomicReference[Instant](Instant.ofEpochMilli(0))

  override def run(): IO[Unit] = IO.fromTry(fetchAndUpdateAirportData())

  def fetchAndUpdateAirportData(): Try[Unit] = {
    val updatedRecently = timeService.timeSince(lastUpdated.get) <= 10.minutes
    if (updatedRecently) {
      return Success(())
    }
    if (lastUpdated.get().isAfter(Instant.now().minusSeconds(60 * 60 * 24))) {
      log.info("Airport data is up to date")
      return Success(())
    }
    for {
      tmpFile: File <- downloader.downloadToTempFile
      _ <- flushTmpFileToDb(tmpFile)
      _ <- Try {
        log.info(s"Stored airport data in db")
        val successfulDeletion = tmpFile.delete()
        log.info(s"Deleted temp file '${tmpFile.getAbsolutePath}' - $successfulDeletion")
      }
    } yield ()
  }

  private def flushTmpFileToDb(tmpFile: File): Try[Unit] = {
    var parser: AirportDataParser = null
    val rowBatch = mutable.ListBuffer[AirportRow]()

    val rowProcessingOutcome = tmpFile.forEachLine {
      case (columnsLine, 0) =>
        parser = parserFactory.fromHeader(columnsLine)
      case (line, _) =>
        parser.parseRow(line) match {
          case Some(row) =>
            rowBatch.append(row)

            if (rowBatch.size >= 1000) {
              flushRowBatch(rowBatch.toList) match {
                case Failure(exception) => throw exception
                case Success(_) => rowBatch.clear()
              }
            }
          case None => log.warn(s"Found row with missing critical fields: $line")
        }
    }

    rowProcessingOutcome match {
      case failure @ Failure(_) => failure
      case Success(_) => flushRowBatch(rowBatch.toList)
    }
  }

  private def flushRowBatch(batch: List[AirportRow]): Try[Unit] = {
    if (batch.isEmpty) return Success(())
    log.info("Flushing row batch to db")
    txnManager.readWriteVoid { txn =>
      batch.map(row => txn.airportsStore.putAirport(row))
    }.recoverWith { exception =>
      log.error("Failed to flush row batch to db", unsafe("batch", batch), unsafe("exception", exception.toString))
      Failure(exception)
    }
  }
}
