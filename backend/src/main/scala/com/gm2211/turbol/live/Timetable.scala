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
import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
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
/**
 * A flight number on a chosen day, from FlightStats' flight pages.
 *
 * @param scheduledOnDay  whether it operates on the chosen day; when not, the times are the ones it flies on
 *                        `operatingDays` (within about three days of today), moved to the chosen day
 * @param callsign        the ATC callsign it flew under recently, when FlightStats tracked it (EK206 flies as UAE5RQ)
 */
final case class DatedFlight(
  flightNumber: String,
  airline: String,
  origin: String,
  destination: String,
  departure: Instant,
  arrival: Option[Instant],
  departureLocal: String,
  arrivalLocal: String,
  arrivalDayOffset: Int,
  codeshares: Seq[String],
  equipment: Option[String],
  callsign: Option[String],
  scheduledOnDay: Boolean,
  operatingDays: Seq[LocalDate]
)

object Timetable extends BackendLogging {
  private val flights: Cache[String, Option[DatedFlight]] =
    CacheBuilder.newBuilder().maximumSize(2000).expireAfterWrite(Duration.ofHours(1)).build()

  private val flightNumber = "^([A-Z0-9]{2})\\s*(\\d{1,4})[A-Z]?$".r

  /** "DL286" -> ("DL", "286"); None for anything that isn't an IATA flight number (callsigns like DAL286). */
  def parseFlightNumber(q: String): Option[(String, String)] =
    q.trim.toUpperCase match {
      case flightNumber(carrier, number) if carrier.exists(_.isLetter) => Some(carrier -> number.dropWhile(_ == '0'))
      case _ => None
    }

  /**
   * Where and when flight `q` (an IATA flight number) flies on `day`, the origin's local date: its own page for that
   * day when it's within reach, else the days around today it operates, the chosen day given the same times.
   */
  def dated(q: String, day: LocalDate, today: LocalDate): Option[DatedFlight] =
    parseFlightNumber(q).flatMap { case (carrier, number) =>
      Try(flights.get(s"$carrier$number-$day", () => fetchDated(carrier, number, day, today))).toOption.flatten
    }

  private val api = "https://www.flightstats.com/v2/api-next/flight-tracker"

  private def fetchDated(carrier: String, number: String, day: LocalDate, today: LocalDate): Option[DatedFlight] = {
    val inReach = !day.isBefore(today.minusDays(windowDays)) && !day.isAfter(today.plusDays(windowDays))
    val own =
      if (!inReach) None
      else
        Try(Http.getString(
          s"$api/$carrier/$number/${day.getYear}/${day.getMonthValue}/${day.getDayOfMonth}",
          attempts = 2
        ))
          .toOption
          .flatMap(parseFlightPage)
    own.orElse(
      Try(Http.getString(s"$api/other-days/$carrier/$number", attempts = 2)).toOption.flatMap(body =>
        parseOtherDays(body, day)
      )
    )
  }

  private def json(body: String) = parse(body).getOrElse(Json.Null).hcursor

  /** A flight page (`api-next/flight-tracker/DL/286/2026/10/12`): its schedule, airports, equipment and callsign. */
  private[live] def parseFlightPage(body: String): Option[DatedFlight] = {
    val d = json(body).downField("data")
    for {
      sched <- Some(d.downField("schedule"))
      dep <- sched.get[String]("scheduledDepartureUTC").toOption.flatMap(s => Try(Instant.parse(s)).toOption)
      from <- d.downField("departureAirport").get[String]("iata").toOption
      to <- d.downField("arrivalAirport").get[String]("iata").toOption
      carrier <- d.downField("resultHeader").downField("carrier").get[String]("fs").toOption
      number <- d.downField("resultHeader").get[String]("flightNumber").toOption
    } yield {
      val arr = sched.get[String]("scheduledArrivalUTC").toOption.flatMap(s => Try(Instant.parse(s)).toOption)
      val localDay = (side: String) =>
        d.downField(side).get[String]("date").toOption.flatMap(s => Try(LocalDateTime.parse(s).toLocalDate).toOption)
      val time24 = (side: String) =>
        d.downField(side).downField("times").downField("scheduled").get[String]("time24").getOrElse("")
      val offset = (for {
        a <- localDay("departureAirport")
        b <- localDay("arrivalAirport")
      } yield Period.between(a, b).getDays).getOrElse(0)
      val codeshares = d.downField("codeshares").values.getOrElse(Iterable.empty).toSeq.flatMap { c =>
        for {
          fs <- c.hcursor.get[String]("fs").toOption
          n <- c.hcursor.get[String]("flightNumber").toOption
        } yield s"$fs$n"
      }
      val track = d.downField("positional").downField("flexTrack")
      DatedFlight(
        flightNumber = s"$carrier$number",
        airline = d.downField("resultHeader").downField("carrier").get[String]("name").getOrElse(carrier),
        origin = from,
        destination = to,
        departure = dep,
        arrival = arr,
        departureLocal = time24("departureAirport"),
        arrivalLocal = time24("arrivalAirport"),
        arrivalDayOffset = offset,
        codeshares = codeshares,
        equipment = d.downField("additionalFlightInfo").downField("equipment").get[String]("name").toOption,
        callsign = track.get[String]("callsign").toOption.map(_.trim.toUpperCase).filter(_.nonEmpty),
        scheduledOnDay = true,
        operatingDays = Seq.empty
      )
    }
  }

  /**
   * The flight's days around today (`api-next/flight-tracker/other-days/DL/286`): when `day` is one of them, that day's
   * flight; otherwise the nearest day's, its times moved to `day`.
   */
  private[live] def parseOtherDays(body: String, day: LocalDate): Option[DatedFlight] = {
    val rows = json(body).downField("data").values.getOrElse(Iterable.empty).toSeq.flatMap { dayRow =>
      dayRow.hcursor.downField("flights").values.getOrElse(Iterable.empty).toSeq.flatMap { f =>
        val c = f.hcursor
        for {
          sort <- c.get[String]("sortTime").toOption.flatMap(s => Try(Instant.parse(s)).toOption)
          from <- c.downField("departureAirport").get[String]("iata").toOption
          to <- c.downField("arrivalAirport").get[String]("iata").toOption
          dep24 <- c.get[String]("departureTime24").toOption
          arr24 <- c.get[String]("arrivalTime24").toOption
          url <- c.get[String]("url").toOption
          localDay <- "year=(\\d+)&month=(\\d+)&date=(\\d+)".r.findFirstMatchIn(url).map(m =>
            LocalDate.of(m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)
          )
        } yield (localDay, sort, from, to, dep24, arr24, url)
      }
    }
    val days = rows.map(_._1).distinct.sorted
    rows.sortBy(r => math.abs(java.time.temporal.ChronoUnit.DAYS.between(day, r._1))).headOption.map {
      case (localDay, sort, from, to, dep24, arr24, url) =>
        val carrierNumber = "/flight-tracker/([A-Z0-9]+)/(\\d+)".r.findFirstMatchIn(url)
        DatedFlight(
          flightNumber = carrierNumber.map(m => m.group(1) + m.group(2)).getOrElse(""),
          airline = carrierNumber.map(_.group(1)).getOrElse(""),
          origin = from,
          destination = to,
          departure = sort.plus(java.time.Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(localDay, day))),
          arrival = None,
          departureLocal = dep24,
          arrivalLocal = arr24,
          arrivalDayOffset = if (arr24 < dep24) 1 else 0,
          codeshares = Seq.empty,
          equipment = None,
          callsign = None,
          scheduledOnDay = localDay == day,
          operatingDays = days
        )
    }
  }

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

  private val pagePool = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))

  private def fetch(from: String, to: String, day: LocalDate): Seq[TimetableFlight] = {
    // The four six-hour pages at once: one after another they took about 3 s on a cold route.
    val requests = Seq(0, 6, 12, 18).map { hour =>
      val url = s"https://www.flightstats.com/v2/flight-tracker/route/$from/$to" +
        s"?year=${day.getYear}&month=${day.getMonthValue}&date=${day.getDayOfMonth}&hour=$hour"
      Future(parsePage(Http.getString(url, attempts = 2)))(using pagePool)
    }
    val pages = Await.result(Future.sequence(requests)(using implicitly, pagePool), 60.seconds)
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
