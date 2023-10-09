/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background.airportdata

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.StringUtils

import scala.collection.mutable

/**
 * Parses a row of the airports.csv file.
 *
 * Csv schema:
 * "id",
 * "ident",
 * "type",
 * "name",
 * "latitude_deg",
 * "longitude_deg",
 * "elevation_ft",
 * "continent",
 * "iso_country",
 * "iso_region",
 * "municipality",
 * "scheduled_service",
 * "gps_code", // = icao_code
 * "iata_code",
 * "local_code",
 * "home_link",
 * "wikipedia_link",
 * "keywords"
 */
trait AirportDataParser {
  def parseRow(line: String): Option[AirportRow]
}

trait AirportDataParserFactory {
  def fromHeader(header: String): AirportDataParser
}

object AirportDataParser extends StringUtils {
  def tokenize(str: String): List[String] = {
    val tokens = mutable.ListBuffer[String]()
    var token = ""
    var inQuotes = false
    var escaped = false
    str.foreach { char =>
      if (escaped) {
        token += char
        escaped = false
      } else if (char == '\\') {
        escaped = true
      } else if (char == '"') {
        inQuotes = !inQuotes
      } else if (char == ',' && !inQuotes) {
        tokens.append(token)
        token = ""
      } else {
        token += char
      }
    }
    tokens.append(token)
    tokens.map(_.quotesStripped).toList
  }

  object CsvColumnNames {
    val icaoColumnName = "gps_code"
    val iataCodeColumnName = "iata_code"
    val nameColumnName = "name"
    val typeColumnName = "type"
    val latitudeDegColumnName = "latitude_deg"
    val longitudeDegColumnName = "longitude_deg"
    val municipalityColumnName = "municipality"
    val isoCountryColumnName = "iso_country"
    val localCodeColumnName = "local_code"
    val keywordsColumnName = "keywords"
  }
}

final class AirportDataParserImpl(idxByColName: Map[String, Int])
    extends AirportDataParser with StringUtils with BackendLogging {
  import AirportDataParser.*
  override def parseRow(line: String): Option[AirportRow] = {
    val columnValues = AirportDataParser.tokenize(line)

    def getColValue(colName: String): Option[String] = {
      val maybeIdx = idxByColName.get(colName)
      maybeIdx match {
        case Some(idx) if (idx < columnValues.length) => columnValues(idx).toOption
        case Some(idx) =>
          log.warn(s"Index '$idx' for column '$colName' is out of bounds")
          None
        case None =>
          log.warn(s"Index for column '$colName' not found")
          None
      }
    }

    for {
      icaoCode <- getColValue(CsvColumnNames.icaoColumnName)
      iataCode <- getColValue(CsvColumnNames.iataCodeColumnName)
      airportName = getColValue(CsvColumnNames.nameColumnName)
      airportType = getColValue(CsvColumnNames.typeColumnName)
      latitudeDeg = getColValue(CsvColumnNames.latitudeDegColumnName).flatMap(_.toDoubleOption)
      longitudeDeg = getColValue(CsvColumnNames.longitudeDegColumnName).flatMap(_.toDoubleOption)
      municipality = getColValue(CsvColumnNames.municipalityColumnName)
      isoCountry = getColValue(CsvColumnNames.isoCountryColumnName)
      localCode = getColValue(CsvColumnNames.localCodeColumnName)
      keywords = getColValue(CsvColumnNames.keywordsColumnName)
        .map(_.split(",").map(_.trim).toList)
        .getOrElse(List[String]())
    } yield AirportRow(
      icaoCode = icaoCode,
      iataCode = iataCode,
      airportName = airportName,
      airportType = airportType,
      latitudeDeg = latitudeDeg,
      longitudeDeg = longitudeDeg,
      municipality = municipality,
      isoCountry = isoCountry,
      localCode = localCode,
      keywords = keywords
    )
  }
}

final class AirportDataParserFactoryImpl() extends AirportDataParserFactory with StringUtils {
  override def fromHeader(header: String): AirportDataParserImpl = {
    val idxByCol = mutable.Map[String, Int]()

    AirportDataParser.tokenize(header)
      .map(_.strip().quotesStripped.strip())
      .zipWithIndex
      .foreach { (column, idx) => idxByCol(column) = idx }

    AirportDataParserImpl(idxByCol.toMap)
  }
}
