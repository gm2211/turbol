package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.objects.internal.model.airports.ICAOCode
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.TestWithDb
import org.scalatest.funsuite.AnyFunSuite

class AirportsStoreImplTest extends TestWithDb {

  test("should be able to store and retrieve an airport") {
    val expected = AirportRow(
      icaoCode = "KJFK",
      iataCode = "JFK",
      airportName = Some("John F Kennedy International Airport"),
      airportType = Some("large_airport"),
      latitudeDeg = Some(40.63980103),
      longitudeDeg = Some(-73.77890015),
      isoCountry = Some("US"),
      localCode = Some("JFK"),
      keywords = List("JFK", "John F Kennedy International Airport")
    )

    val actual: Option[AirportRow] = txnManager.readWrite { txn =>
      for {
        _ <- txn.airportsStore.putAirport(expected)
        airport <- txn.airportsStore.getAirport(ICAOCode("KJFK"))
      } yield airport
    }.value
    actual should contain(expected.copy(keywords = List()))
  }

  test("getting an airport that does not exist should return None") {
    val maybeAirport = txnManager.readWrite { txn => txn.airportsStore.getAirport(ICAOCode("KJFK")) }.value
    maybeAirport shouldBe empty
  }

}
