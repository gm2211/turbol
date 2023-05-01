package com.gm2211.turbol.storage.stores

import com.gm2211.turbol.objects.internal.model.airports.ICAOCode
import com.gm2211.turbol.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.TestWithDb

class AirportsStoreImplTest extends TestWithDb {
  private val testAirport = AirportRow(
    icaoCode = "KJFK",
    iataCode = "JFK",
    airportName = Some("John F Kennedy International Airport"),
    airportType = Some("large_airport"),
    latitudeDeg = Some(40.63980103),
    longitudeDeg = Some(-73.77890015),
    municipality = Some("New York"),
    isoCountry = Some("US"),
    localCode = Some("JFK"),
    keywords = List("JFK", "John", "F", "Kennedy", "International", "Airport", "Comfortable")
  )

  test("should be able to store and retrieve an airport") {
    val actual: Option[AirportRow] = txnManager.readWrite { txn =>
      for {
        _ <- txn.airportsStore.putAirport(testAirport)
        airport <- txn.airportsStore.getAirport(ICAOCode("KJFK"))
      } yield airport
    }.value
    actual should contain(testAirport.copy(keywords = List()))
  }

  test("should be able to overwrite and retrieve an airport") {
    val airportWithOtherName = testAirport.copy(airportName = Some("Some other name"))
    val actual: Option[AirportRow] = txnManager.readWrite { txn =>
      for {
        _ <- txn.airportsStore.putAirport(testAirport)
        _ <- txn.airportsStore.putAirport(airportWithOtherName)
        airport <- txn.airportsStore.getAirport(ICAOCode("KJFK"))
      } yield airport
    }.value
    actual should contain(airportWithOtherName.copy(keywords = List()))
  }

  test("getting an airport that does not exist should return None") {
    val maybeAirport = txnManager.readWrite { txn => txn.airportsStore.getAirport(ICAOCode("KJFK")) }.value
    maybeAirport shouldBe empty
  }

  test("searching an airport finds airport if matches any keyword case-insensitively") {
    txnManager.readWriteVoid { txn => txn.airportsStore.putAirport(testAirport).just }.value
    
    val maybeFound: List[AirportRow] = txnManager.readOnly { txn => txn.airportsStore.search("comfortable") }.value
    
    maybeFound should contain(testAirport.copy(keywords = List()))
  }
}
