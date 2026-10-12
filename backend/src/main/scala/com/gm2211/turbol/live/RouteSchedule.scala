/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.AircraftClass
import com.google.common.cache.{Cache, CacheBuilder}

import java.time.{Duration, Instant}
import java.util.concurrent.Executors
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * One flight on a route, as flown in the last day: what the Analyze page lists for a route and date.
 *
 * @param takeoff     when it is expected to take off on the requested day: the observed takeoff's time of day
 * @param flownTakeoff the observed takeoff the path comes from; with `hex` it names the leg for the analysis
 */
final case class ScheduledFlight(
  callsign: String,
  flightNumber: Option[String],
  airline: Option[String],
  takeoff: Instant,
  landing: Instant,
  typeCode: Option[String],
  aircraftClass: AircraftClass,
  registration: Option[String],
  hex: String,
  flownTakeoff: Instant
)

final case class RouteSchedule(flights: List[ScheduledFlight], candidates: Int, slotsScanned: Int, note: Option[String])

/**
 * Flights on a route for a given day, predicted from the last day of adsb.lol history: callsigns the route directory
 * lists for the airport pair, found in the half-hour heatmap slots (callsign -> airframe), then their flown leg in the
 * airframe's trace. Airline timetables are daily for most routes, so yesterday's takeoff time of day is the forecast
 * for any day; filed flight plans aren't public.
 */
final class RouteScheduleService(traffic: LiveTrafficService, directory: RouteDirectory = RouteDirectory())
    extends BackendLogging {
  private given ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))
  private val flown: Cache[String, Seq[FlownLeg]] =
    CacheBuilder.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(30)).build()

  /** Legs flown from origin to destination in the last day, latest per callsign. */
  def flownLegs(origin: Place, destination: Place): (Seq[FlownLeg], Int, Int) = {
    val (from, to) = (icao(origin), icao(destination))
    val candidates = (for {
      f <- from
      t <- to
    } yield directory.callsigns(f, t)).getOrElse(Seq.empty)
    if (candidates.isEmpty) return (Seq.empty, 0, 0)
    val slotTimes = FlightHistory.slotsToScan()
    val legs = flown.get(
      s"${from.get}-${to.get}",
      () => {
        val slots = Await.result(
          Future.traverse(slotTimes)(t => Future(t -> Try(FlightHistory.slot(t)).toOption)),
          3.minutes
        )
        // Newest sighting per callsign: the airframe that flew it most recently.
        val sightings = candidates.flatMap { cs =>
          slots.collectFirst { case (t, Some(index)) if index.hex(cs).isDefined => (cs, index.hex(cs).get, t) }
        }
        val found = Await.result(
          Future.traverse(sightings) { case (cs, hex, seen) =>
            Future(Try(FlightHistory.findLeg(cs, hex, seen.plusSeconds(900), origin, destination)).toOption.flatten)
          },
          2.minutes
        )
        found.flatten.sortBy(_.takeoff)
      }
    )
    (legs, candidates.size, slotTimes.size)
  }

  /** Flights taking off between `from` and `until` (the person's local day), from the legs flown in the last day. */
  def schedule(origin: Place, destination: Place, from: Instant, until: Instant): RouteSchedule = {
    val (legs, candidates, slotsScanned) = flownLegs(origin, destination)
    val flights = legs.flatMap { leg =>
      RouteScheduleService.onDay(leg.takeoff, from, until).map { takeoff =>
        val route = traffic.route(leg.callsign)
        ScheduledFlight(
          callsign = leg.callsign,
          flightNumber = route.flatMap(_.iataCallsign),
          airline = route.flatMap(_.airline),
          takeoff = takeoff,
          landing = takeoff.plus(Duration.between(leg.takeoff, leg.landing)),
          typeCode = leg.typeCode,
          aircraftClass = AircraftClass.fromAdsb(None, leg.typeCode),
          registration = leg.registration,
          hex = leg.hex,
          flownTakeoff = leg.takeoff
        )
      }
    }.sortBy(_.takeoff).toList
    val note =
      if (origin.icao.isEmpty && origin.code.length != 4 || destination.icao.isEmpty && destination.code.length != 4)
        Some("Couldn't tell the airports' ICAO codes, so no flights could be looked up.")
      else if (candidates == 0) Some("No airline flight numbers are listed for this route.")
      else if (flights.isEmpty) Some("None of this route's flight numbers flew in the last day.")
      else None
    RouteSchedule(flights, candidates, slotsScanned, note)
  }

  /** The leg flown on airframe `hex` that took off at `flownTakeoff`, for the analysis. */
  def leg(callsign: String, hex: String, flownTakeoff: Instant, origin: Place, destination: Place): Option[FlownLeg] =
    flownLegs(origin, destination)._1
      .find(l => l.hex.equalsIgnoreCase(hex) && l.takeoff == flownTakeoff)
      .orElse(FlightHistory.findLeg(callsign, hex, flownTakeoff.plusSeconds(600), origin, destination))

  private def icao(place: Place): Option[String] =
    place.icao.filter(_.length == 4).orElse(Some(place.code.toUpperCase).filter(_.length == 4))
}

object RouteScheduleService {
  /** The same time of day as `observed`, on the day [from, until), if it falls inside it. */
  def onDay(observed: Instant, from: Instant, until: Instant): Option[Instant] = {
    val day = 86400L
    val offset = Math.floorMod(observed.getEpochSecond - from.getEpochSecond, day)
    Some(from.plusSeconds(offset)).filter(_.isBefore(until))
  }
}
