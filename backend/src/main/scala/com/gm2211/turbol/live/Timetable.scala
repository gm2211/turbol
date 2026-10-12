/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.Http
import com.google.common.cache.{Cache, CacheBuilder}
import io.circe.Json
import io.circe.parser.parse

import java.time.*
import java.time.format.DateTimeFormatter
import scala.util.Try

/**
 * One scheduled flight on a route: gate departure and arrival as the airline publishes them.
 *
 * @param departureLocal "16:40" at the origin
 * @param arrivalLocal   "06:45" at the destination, `arrivalDayOffset` days after departure (by the local calendars)
 * @param codeshares     other flight numbers sold on it ("AF8833")
 */
final case class TimetableFlight(
  flightNumber: String,
  carrierIata: String,
  number: String,
  airline: String,
  departure: Instant,
  arrival: Instant,
  departureLocal: String,
  arrivalLocal: String,
  arrivalDayOffset: Int,
  codeshares: Seq[String]
)

/**
 * A route's timetable for one day, from FlightStats' public route pages (the data embedded in
 * `flightstats.com/v2/flight-tracker/route/JFK/MXP?year=..&month=..&date=..&hour=..`, one page per six hours). They
 * cover about three days either side of today; further out, the same weekday a week earlier stands in, since most
 * timetables repeat weekly. This is the website's own page data, not a documented API, so it can change or go away:
 * callers fall back to what ADS-B saw.
 */
object Timetable extends BackendLogging {
  private val cache: Cache[String, Seq[TimetableFlight]] =
    CacheBuilder.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofHours(6)).build()

  /** How far from today the pages answer. */
  val windowDays = 3

  /**
   * The flights departing `fromIata` on local day `day` for `toIata`, and the day the timetable was read for (`day`, or
   * the same weekday a week or two earlier when `day` is beyond the pages' reach). None when the pages can't be read.
   */
  def route(
    fromIata: String,
    toIata: String,
    day: LocalDate,
    today: LocalDate
  ): Option[(Seq[TimetableFlight], LocalDate)] = {
    val readDay = Iterator.iterate(day)(_.minusWeeks(1)).take(3).find(d => !d.isAfter(today.plusDays(windowDays)))
    readDay.filterNot(_.isBefore(today.minusDays(windowDays))).flatMap { d =>
      val key = s"${fromIata.toUpperCase}-${toIata.toUpperCase}-$d"
      Try(cache.get(key, () => fetch(fromIata.toUpperCase, toIata.toUpperCase, d))).toOption.map(_ -> d)
    }
  }

  private def fetch(from: String, to: String, day: LocalDate): Seq[TimetableFlight] = {
    val pages = Seq(0, 6, 12, 18).map { hour =>
      val url = s"https://www.flightstats.com/v2/flight-tracker/route/$from/$to" +
        s"?year=${day.getYear}&month=${day.getMonthValue}&date=${day.getDayOfMonth}&hour=$hour"
      parsePage(Http.getString(url, attempts = 2))
    }
    // Each page also lists late flights from the previous evening still under way: keep this day's departures.
    pages.flatten.filter(_._1 == day).map(_._2).distinctBy(f => (f.flightNumber, f.departure))
  }

  private val dataStart = "__NEXT_DATA__ = "

  /** A route page's flights, each with its local departure day; codeshares folded into the flight that operates them. */
  private[live] def parsePage(html: String): Seq[(LocalDate, TimetableFlight)] = {
    val start = html.indexOf(dataStart)
    if (start < 0) return Seq.empty
    val end = html.indexOf(";__NEXT_LOADED_PAGES__", start)
    val json = parse(html.substring(start + dataStart.length, if (end > 0) end else html.length)).getOrElse(Json.Null)
    val route = json.hcursor.downField("props").downField("initialState").downField("flightTracker").downField("route")
    val zone = (side: String) =>
      route.downField("header").downField(side).get[String]("timeZoneRegionName").toOption.flatMap(z =>
        Try(ZoneId.of(z)).toOption
      )
    (zone("departureAirport"), zone("arrivalAirport")) match {
      case (Some(fromZone), Some(toZone)) =>
        val rows = route.downField("flights").values.getOrElse(Iterable.empty).toSeq.map(_.hcursor)
        val (shares, own) = rows.partition(_.get[Boolean]("isCodeshare").getOrElse(false))
        def number(c: io.circe.ACursor) =
          (
            c.downField("carrier").get[String]("fs").toOption,
            c.downField("carrier").get[String]("flightNumber").toOption
          )
        own.flatMap { c =>
          for {
            sort <- c.get[String]("sortTime").toOption.flatMap(s => Try(Instant.parse(s)).toOption)
            (fs, n) <- number(c) match {
              case (Some(fs), Some(n)) => Some(fs -> n)
              case _ => None
            }
            depLocal <- c.downField("departureTime").get[String]("time24").toOption
            arrLocal <- c.downField("arrivalTime").get[String]("time24").toOption
            arrTime <- Try(LocalTime.parse(arrLocal, DateTimeFormatter.ofPattern("H:mm"))).toOption
          } yield {
            val departure = sort.atZone(fromZone)
            // The first time the destination's clock reads arrLocal after departure (flights are under a day).
            val sameDay = departure.withZoneSameInstant(toZone).`with`(arrTime)
            val arrival = if (sameDay.toInstant.isAfter(sort)) sameDay else sameDay.plusDays(1)
            val offset = Period.between(departure.toLocalDate, arrival.toLocalDate).getDays
            val name = c.downField("carrier").get[String]("name").getOrElse(fs)
            val codeshares = shares.filter { s =>
              s.get[String]("sortTime").toOption.contains(c.get[String]("sortTime").getOrElse("")) &&
              s.get[String]("operatedBy").toOption.exists(_.trim.endsWith(s" $n"))
            }.flatMap(s =>
              number(s) match {
                case (Some(sfs), Some(sn)) => Some(s"$sfs$sn")
                case _ => None
              }
            )
            departure.toLocalDate -> TimetableFlight(
              s"$fs$n",
              fs,
              n,
              name,
              sort,
              arrival.toInstant,
              depLocal,
              arrLocal,
              offset,
              codeshares
            )
          }
        }
      case _ => Seq.empty
    }
  }
}
