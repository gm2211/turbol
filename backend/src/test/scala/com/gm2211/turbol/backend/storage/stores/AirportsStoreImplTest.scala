package com.gm2211.turbol.backend.storage.stores

import cats.effect.IO
import com.gm2211.reactive.Refreshable
import com.gm2211.turbol.backend.config.runtime.{DatabaseConfig, LoggingConfig, RuntimeConfig}
import com.gm2211.turbol.backend.objects.internal.model.airports.{Airport, IATACode, ICAOCode}
import com.gm2211.turbol.backend.objects.internal.storage.airports.AirportRow
import com.gm2211.turbol.util.{TestTxnManager, TestWithDb}
import doobie.syntax.ConnectionIOOps
import doobie.{syntax, ConnectionIO, WeakAsync}
import org.scalatest.funsuite.AnyFunSuite

import scala.language.implicitConversions

class AirportsStoreImplTest extends TestWithDb {

  test("should be able to store and retrieve an airport") {
    val expected = AirportRow(
      displayId = "JFK",
      airportType = "large_airport",
      name = "John F Kennedy International Airport",
      latitudeDeg = 40.63980103,
      longitudeDeg = -73.77890015,
      isoCountry = Some("US"),
      icaoCode = "KJFK",
      iataCode = "JFK",
      localCode = Some("JFK"),
      keywords = List("JFK", "John F Kennedy International Airport")
    )

    val actual: Option[AirportRow] = txnManager.readWrite { txn =>
      for {
        _ <- txn.airportsStore.putAirport(expected)
        airport <- txn.airportsStore.getAirport(ICAOCode("KJFK"))
      } yield airport
    }
    actual should contain(expected.copy(keywords = List()))
  }

  test("getting an airport that does not exist should return None") {
    val maybeAirport = txnManager.readWrite { txn => txn.airportsStore.getAirport(ICAOCode("KJFK")) }
    maybeAirport shouldBe empty
  }

}
