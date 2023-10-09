/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.background

import com.gm2211.turbol.background.airportdata.{AirportDataParser, AirportDataParserImpl}
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.BaseTest

class AirportDataParserImplTest extends BaseTest {
  test("correctly parses a row") {
    val row =
      """6734,"03OI","heliport","Cleveland Clinic, Marymount Hospital Heliport",41.420312,-81.599552,890,"NA","US","""
        + """"US-OH","Garfield Heights","no","03OI","FAKE","03OI",,,""".stripMargin

    val parsed = AirportDataParserImpl(
      Map(
        AirportDataParser.CsvColumnNames.typeColumnName -> 2,
        AirportDataParser.CsvColumnNames.nameColumnName -> 3,
        AirportDataParser.CsvColumnNames.latitudeDegColumnName -> 4,
        AirportDataParser.CsvColumnNames.longitudeDegColumnName -> 5,
        AirportDataParser.CsvColumnNames.isoCountryColumnName -> 8,
        AirportDataParser.CsvColumnNames.icaoColumnName -> 12,
        AirportDataParser.CsvColumnNames.iataCodeColumnName -> 13,
        AirportDataParser.CsvColumnNames.localCodeColumnName -> 14,
        AirportDataParser.CsvColumnNames.municipalityColumnName -> 10,
        AirportDataParser.CsvColumnNames.keywordsColumnName -> 17
      )
    ).parseRow(row)
    parsed should contain(AirportRow(
      "03OI",
      "FAKE",
      Some("Cleveland Clinic, Marymount Hospital Heliport"),
      Some("heliport"),
      Some(41.420312),
      Some(-81.599552),
      Some("Garfield Heights"),
      Some("US"),
      Some("03OI"),
      List()
    ))
  }
}
