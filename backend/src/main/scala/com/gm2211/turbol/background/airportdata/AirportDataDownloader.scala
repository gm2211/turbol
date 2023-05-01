package com.gm2211.turbol.background.airportdata

import com.gm2211.logging.BackendLogging
import org.apache.commons.io.FileUtils

import java.io.File
import java.net.URL
import java.nio.file.Files
import scala.concurrent.duration.*
import scala.util.{Failure, Try}

trait AirportDataDownloader {
  def downloadToTempFile: Try[File]
}
final class AirportDataDownloaderImpl extends AirportDataDownloader with BackendLogging {
  private val url = URL("https://davidmegginson.github.io/ourairports-data/airports.csv")

  def downloadToTempFile: Try[File] = {
    val tmpFile = Files.createTempFile("airports", ".csv").toFile
    tmpFile.deleteOnExit()
    Try {
      log.info(s"Downloading airport data to temp file: ${tmpFile.getAbsolutePath}")
      FileUtils.copyURLToFile(url, tmpFile, 5.minutes.toMillis.toInt, 5.minutes.toMillis.toInt)
      log.info(s"Downloaded airport data to temp file: ${tmpFile.getAbsolutePath}")
      tmpFile
    }.recoverWith { exception =>
      log.error(s"Failed to download airport data to temp file: ${tmpFile.getAbsolutePath}", exception)
      tmpFile.delete()
      Failure(exception)
    }
  }

}
