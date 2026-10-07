/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.turbol.turbulence.Http
import io.circe.{ACursor, HCursor, Json}
import io.circe.parser.parse

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.util.Try

/** One aircraft as reported by an ADS-B aggregator. Altitudes are barometric feet, speeds knots. */
final case class LiveAircraft(
  hex: String,
  callsign: Option[String],
  registration: Option[String],
  typeCode: Option[String],
  category: Option[String],
  lat: Double,
  lon: Double,
  altitudeFt: Option[Int],
  onGround: Boolean,
  groundSpeedKts: Option[Double],
  trackDeg: Option[Double],
  verticalRateFpm: Option[Int],
  seenSecondsAgo: Double
)

/** A scheduled route for a callsign. */
final case class Place(code: String, name: String, lat: Double, lon: Double)
final case class FlightRoute(
  callsign: String,
  iataCallsign: Option[String],
  airline: Option[String],
  origin: Place,
  destination: Place
)

/**
 * Keyless public data sources:
 *   - positions: adsb.lol (`/v2/lat/{lat}/lon/{lon}/dist/{nm}`, `/v2/hex`, `/v2/callsign`), ODbL, readsb JSON format
 *   - routes: adsbdb.com (`/v0/callsign/{callsign}`), which also accepts IATA flight numbers like "UA1"
 */
object LiveTrafficClient {
  private val adsbLol = "https://api.adsb.lol/v2"
  private val adsbDb = "https://api.adsbdb.com/v0"

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  /**
   * adsb.lol rate-limits per client: measured ~22 requests at 1/s before a 429, so every call goes through this token
   * bucket (burst of 15, then one request every 2.5 s, i.e. 24/min sustained).
   */
  private object Throttle {
    private val capacity = 15.0
    private val refillMs = 2500.0
    private var tokens = capacity
    private var last = System.currentTimeMillis()

    def acquire(): Unit = synchronized {
      val now = System.currentTimeMillis()
      tokens = math.min(capacity, tokens + (now - last) / refillMs)
      last = now
      if (tokens < 1) {
        Thread.sleep(((1 - tokens) * refillMs).toLong)
        last = System.currentTimeMillis()
        tokens = 1
      }
      tokens -= 1
    }

    def backOff(ms: Long): Unit = synchronized {
      tokens = -ms / refillMs
      last = System.currentTimeMillis()
    }
  }

  private def adsbLolGet(url: String): String = {
    Throttle.acquire()
    try Http.getString(url, attempts = 1)
    catch {
      case e: RuntimeException if Option(e.getMessage).exists(_.startsWith("HTTP 429")) =>
        Throttle.backOff(15000)
        throw e
    }
  }

  def aircraftAround(lat: Double, lon: Double, radiusNm: Int): (Instant, Seq[LiveAircraft]) =
    parseAircraftList(adsbLolGet(f"$adsbLol/lat/$lat%.4f/lon/$lon%.4f/dist/$radiusNm"))

  def byHex(hex: String): Option[LiveAircraft] =
    parseAircraftList(adsbLolGet(s"$adsbLol/hex/${enc(hex.toLowerCase)}"))._2.headOption

  def byCallsign(callsign: String): Seq[LiveAircraft] =
    parseAircraftList(adsbLolGet(s"$adsbLol/callsign/${enc(callsign.toUpperCase)}"))._2

  /**
   * ICAO callsigns to try for what a person typed: "UAL1" as is; an IATA flight number like "UA1" or "ua 1" expanded
   * via adsbdb's airline table to "UAL1" (several airlines can share an IATA code).
   */
  def candidateCallsigns(query: String): Seq[String] = {
    val q = query.replaceAll("\\s+", "").toUpperCase
    val iata = """^([A-Z0-9]{2})(\d{1,4}[A-Z]?)$""".r
    q match {
      case iata(airline, number) if !airline.forall(_.isDigit) =>
        val icaos = Try(Http.getString(s"$adsbDb/airline/${enc(airline)}", attempts = 2)).toOption
          .flatMap(body => parse(body).toOption)
          .flatMap(_.hcursor.downField("response").values)
          .getOrElse(Seq.empty)
          .flatMap(_.hcursor.get[String]("icao").toOption)
          .toSeq
        icaos.map(_ + number.dropWhile(_ == '0')) :+ q
      case _ => Seq(q)
    }
  }

  def route(callsign: String): Option[FlightRoute] =
    Try(Http.getString(s"$adsbDb/callsign/${enc(callsign.toUpperCase)}", attempts = 2)).toOption
      .flatMap(body => parse(body).toOption)
      .flatMap(json => parseRoute(json.hcursor.downField("response").downField("flightroute")))

  private[live] def parseRoute(c: ACursor): Option[FlightRoute] = {
    def place(field: String): Option[Place] = {
      val p = c.downField(field)
      for {
        lat <- p.get[Double]("latitude").toOption
        lon <- p.get[Double]("longitude").toOption
        code = p.get[String]("iata_code").toOption.orElse(p.get[String]("icao_code").toOption).getOrElse("?")
        name = p.get[String]("name").toOption.getOrElse(code)
      } yield Place(code, name, lat, lon)
    }
    for {
      callsign <- c.get[String]("callsign_icao").toOption.orElse(c.get[String]("callsign").toOption)
      origin <- place("origin")
      destination <- place("destination")
    } yield FlightRoute(
      callsign,
      c.get[String]("callsign_iata").toOption,
      c.downField("airline").get[String]("name").toOption,
      origin,
      destination
    )
  }

  private[live] def parseAircraftList(body: String): (Instant, Seq[LiveAircraft]) = {
    val json = parse(body).getOrElse(Json.Null)
    val c = json.hcursor
    val now = c.get[Double]("now").toOption.map(ms => Instant.ofEpochMilli(ms.toLong)).getOrElse(Instant.now())
    val list = c.downField("ac").values.orElse(c.downField("aircraft").values).getOrElse(Seq.empty)
    (now, list.flatMap(j => parseAircraft(j.hcursor)).toSeq)
  }

  private def parseAircraft(c: HCursor): Option[LiveAircraft] = {
    val altBaro = c.downField("alt_baro")
    val onGround = altBaro.as[String].toOption.contains("ground")
    for {
      hex <- c.get[String]("hex").toOption
      lat <- c.get[Double]("lat").toOption.orElse(c.downField("lastPosition").get[Double]("lat").toOption)
      lon <- c.get[Double]("lon").toOption.orElse(c.downField("lastPosition").get[Double]("lon").toOption)
    } yield LiveAircraft(
      hex = hex,
      callsign = c.get[String]("flight").toOption.map(_.trim).filter(_.nonEmpty),
      registration = c.get[String]("r").toOption,
      typeCode = c.get[String]("t").toOption,
      category = c.get[String]("category").toOption,
      lat = lat,
      lon = lon,
      altitudeFt = altBaro.as[Int].toOption.orElse(c.get[Int]("alt_geom").toOption),
      onGround = onGround,
      groundSpeedKts = c.get[Double]("gs").toOption,
      trackDeg = c.get[Double]("track").toOption.orElse(c.get[Double]("true_heading").toOption),
      verticalRateFpm = c.get[Int]("baro_rate").toOption.orElse(c.get[Int]("geom_rate").toOption),
      seenSecondsAgo = c.get[Double]("seen_pos").toOption.orElse(c.get[Double]("seen").toOption).getOrElse(0.0)
    )
  }
}
