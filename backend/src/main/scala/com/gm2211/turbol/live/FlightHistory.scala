/*
 * Copyright 2020 Giulio Mecocci
 *
 * All rights reserved.
 */

package com.gm2211.turbol.live

import com.gm2211.logging.BackendLogging
import com.gm2211.turbol.turbulence.{Http, RouteAnalyzer}
import com.google.common.cache.{Cache, CacheBuilder}
import io.circe.Json
import io.circe.parser.parse

import java.io.{BufferedInputStream, ByteArrayInputStream, InputStream}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.zip.GZIPInputStream
import scala.util.Try

/** One point of a flown track: barometric altitude in feet. */
final case class TrackPoint(time: Instant, lat: Double, lon: Double, altitudeFt: Double)

/** One leg an aircraft actually flew, takeoff to landing, as adsb.lol recorded it. */
final case class FlownLeg(
  callsign: String,
  hex: String,
  typeCode: Option[String],
  registration: Option[String],
  points: Vector[TrackPoint]
) {
  def takeoff: Instant = points.head.time
  def landing: Instant = points.last.time

  /** Whether the leg starts near `origin` and ends near `destination`. */
  def flies(origin: Place, destination: Place): Boolean =
    near(points.head, origin, FlightHistory.airportKm) && near(points.last, destination, FlightHistory.airportKm)

  private def near(p: TrackPoint, place: Place, km: Double): Boolean =
    RouteAnalyzer.greatCircleKm(p.lat, p.lon, place.lat, place.lon) < km
}

/**
 * What flew recently, from adsb.lol's public history (ODbL), with no API key:
 *   - heatmap slots (`globe_history/YYYY/MM/DD/heatmap/NN.bin.ttf`): every aircraft seen in each half hour, with its
 *     callsign, which maps a flight number to the airframe (ICAO hex) that flew it;
 *   - traces (`data/traces/xx/trace_full_<hex>.json` for the last day or so, `globe_history/.../traces/...` for older
 *     days): that airframe's positions, altitudes and times, cut into legs at each landing.
 */
object FlightHistory extends BackendLogging {
  private val base = "https://adsb.lol"

  /** A leg counts as flying a route when it starts and ends this close to the airports. */
  val airportKm = 30.0

  /** Callsign -> ICAO hex for one half-hour slot, as two sorted arrays (callsigns packed into longs) to stay small. */
  final class SlotIndex(private val keys: Array[Long], private val hexes: Array[Int]) {
    def size: Int = keys.length
    def hex(callsign: String): Option[String] = {
      val k = java.util.Arrays.binarySearch(keys, pack(callsign))
      if (k >= 0) Some(f"${hexes(k)}%06x") else None
    }
  }

  private def pack(callsign: String): Long = {
    val bytes = callsign.trim.toUpperCase.getBytes(StandardCharsets.US_ASCII).take(8)
    bytes.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xff)) << (8 * (8 - bytes.length))
  }

  // Only slots at least an hour old are read, and those are final, so they're kept as long as there's room (about
  // 150 KB each).
  private val slots: Cache[String, SlotIndex] =
    CacheBuilder.newBuilder().maximumSize(150).expireAfterAccess(Duration.ofDays(15)).build()

  private val heatmapMarker = 0x0e7f7c9d

  /** The half-hour slot starting at `start` (UTC, on a :00 or :30), fetched once and cached. */
  def slot(start: Instant): SlotIndex = {
    val t = start.atZone(ZoneOffset.UTC)
    val n = t.getHour * 2 + t.getMinute / 30
    val url =
      f"$base/globe_history/${t.getYear}%04d/${t.getMonthValue}%02d/${t.getDayOfMonth}%02d/heatmap/$n%02d.bin.ttf"
    slots.get(url, () => Http.withStream(url)(parseSlot))
  }

  /**
   * tar1090's heatmap format: little-endian int32 quadruples. A quadruple starting with 0x0e7f7c9d opens a 10-second
   * slice; one whose second int has bit 30 set is a callsign record (hex in the low 24 bits of the first int, eight
   * ASCII characters in the last two ints); anything else is a position.
   */
  private[live] def parseSlot(body: InputStream): SlotIndex = {
    val in = new BufferedInputStream(body, 1 << 16)
    in.mark(2)
    val gzipped = in.read() == 0x1f && in.read() == 0x8b
    in.reset()
    // Streamed 16 bytes at a time: a slot is 10-25 MB unpacked and the server's heap is small.
    val records = if (gzipped) new BufferedInputStream(new GZIPInputStream(in, 1 << 16), 1 << 16) else in
    val record = new Array[Byte](16)
    val buf = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
    val found = new java.util.HashMap[Long, Int]()
    while (records.readNBytes(record, 0, 16) == 16) {
      val first = buf.getInt(0)
      val second = buf.getInt(4)
      if (first != heatmapMarker && second > 0 && ((second >> 30) & 1) == 1) {
        val callsign = new String(record, 8, 8, StandardCharsets.US_ASCII).trim
        if (callsign.nonEmpty && callsign.forall(c => c.isLetterOrDigit))
          found.put(pack(callsign), first & 0xffffff): Unit
      }
    }
    val keys = found.keySet().toArray(new Array[java.lang.Long](0)).map(_.longValue).sorted
    SlotIndex(keys, keys.map(k => found.get(k)))
  }

  /** adsb.lol serves its history files gzipped whatever the request asks for. */
  private def gunzipped(body: Array[Byte]): Array[Byte] =
    if (body.length > 2 && body(0) == 0x1f.toByte && body(1) == 0x8b.toByte)
      new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes()
    else body

  private val traces: Cache[String, Vector[FlownLeg]] =
    CacheBuilder.newBuilder().maximumSize(300).expireAfterWrite(Duration.ofMinutes(20)).build()

  /** Every leg an airframe flew in roughly the last day (adsb.lol's rolling trace), plus `day`'s archive if given. */
  def legs(hex: String, archiveDay: Option[LocalDate] = None): Vector[FlownLeg] = {
    val h = hex.toLowerCase
    val url = archiveDay match {
      case Some(d) =>
        f"$base/globe_history/${d.getYear}%04d/${d.getMonthValue}%02d/${d.getDayOfMonth}%02d/traces/${h.takeRight(2)}/trace_full_$h.json"
      case None => s"$base/data/traces/${h.takeRight(2)}/trace_full_$h.json"
    }
    traces.get(
      url,
      () =>
        Try(parseLegs(new String(gunzipped(Http.getBytes(url, attempts = 2)), StandardCharsets.UTF_8)))
          .getOrElse(Vector.empty)
    )
  }

  /**
   * The leg `callsign` flew on airframe `hex` from `origin` to `destination` that was in the air at `seen`, or took off
   * within two hours after it (seen at the gate or taxiing; or a leg looked up again by its takeoff). The rolling trace covers about the last day; older legs come
   * from the daily archives, and a leg split across files (the rolling trace's start, midnight UTC) is stitched back
   * together. None when the leg at `seen` flew elsewhere (callsigns are reused for onward legs) or hasn't landed yet.
   */
  def findLeg(callsign: String, hex: String, seen: Instant, origin: Place, destination: Place): Option[FlownLeg] = {
    // In the air at `seen`, or about to take off: a sighting can be at the gate or taxiing, up to two hours before.
    def at(pool: Seq[FlownLeg]) = {
      val legs = FlightHistory
        .stitch(pool.filter(_.callsign.equalsIgnoreCase(callsign)))
        .filter(l => !l.takeoff.isAfter(seen.plusSeconds(7200)) && !l.landing.isBefore(seen.minusSeconds(1800)))
      legs.find(l => !l.takeoff.isAfter(seen.plusSeconds(1800))).orElse(legs.headOption)
    }
    val recent = legs(hex)
    val found = at(recent).filter(_.flies(origin, destination)).orElse {
      val day = LocalDate.ofInstant(seen, ZoneOffset.UTC)
      val today = LocalDate.now(ZoneOffset.UTC)
      // Today's archive is only written once the day is over.
      val archived =
        Seq(day.minusDays(1), day, day.plusDays(1)).filter(_.isBefore(today)).flatMap(d => legs(hex, Some(d)))
      at(archived ++ recent)
    }
    found.filter(_.flies(origin, destination))
  }

  /**
   * Joins pieces of one flight cut apart by file boundaries: same callsign, and the next one starts within 30 minutes
   * of the end of the one before (or overlaps it, where the rolling trace and an archive both have it), or within 10
   * hours when both ends are at cruise (the gap is ocean without coverage, across midnight UTC).
   */
  private[live] def stitch(pieces: Seq[FlownLeg]): Seq[FlownLeg] =
    pieces.sortBy(_.takeoff).foldLeft(Vector.empty[FlownLeg]) { (out, leg) =>
      out.lastOption match {
        case Some(prev) if prev.callsign.equalsIgnoreCase(leg.callsign) && joins(prev, leg) =>
          val points = (prev.points ++ leg.points).distinctBy(_.time).sortBy(_.time)
          out.init :+ prev.copy(points = points)
        case _ => out :+ leg
      }
    }

  private def joins(prev: FlownLeg, next: FlownLeg): Boolean = {
    val gap = Duration.between(prev.landing, next.takeoff)
    !gap.minusMinutes(30).isPositive ||
    (!gap.minusHours(10).isPositive && prev.points.last.altitudeFt > 10000 && next.points.head.altitudeFt > 10000)
  }

  /** Splits a readsb trace_full JSON into legs: a leg ends on the ground, at a new-leg flag, or at a long low gap. */
  private[live] def parseLegs(body: String): Vector[FlownLeg] = {
    val json = parse(body).getOrElse(Json.Null)
    val c = json.hcursor
    val hex = c.get[String]("icao").getOrElse("")
    val typeCode = c.get[String]("t").toOption
    val registration = c.get[String]("r").toOption
    val start = c.get[Double]("timestamp").getOrElse(0.0)
    val rows = c.downField("trace").values.getOrElse(Iterable.empty).toVector.flatMap(_.asArray)
    val out = Vector.newBuilder[FlownLeg]
    var current = Vector.newBuilder[TrackPoint]
    var count = 0
    var callsign: Option[String] = None
    var lastTime = 0.0
    var lastAlt = Double.NaN
    def close(): Unit = {
      val points = current.result()
      if (count >= 5) callsign.foreach(cs => out += FlownLeg(cs, hex, typeCode, registration, points))
      current = Vector.newBuilder[TrackPoint]
      count = 0
      callsign = None
    }
    rows.foreach { row =>
      val time = start + row.lift(0).flatMap(_.asNumber).fold(0.0)(_.toDouble)
      val lat = row.lift(1).flatMap(_.asNumber).map(_.toDouble)
      val lon = row.lift(2).flatMap(_.asNumber).map(_.toDouble)
      val ground = row.lift(3).flatMap(_.asString).contains("ground")
      val alt = row.lift(3).flatMap(_.asNumber).map(_.toDouble)
      val flags = row.lift(6).flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
      val flight = row.lift(8).flatMap(_.hcursor.get[String]("flight").toOption).map(_.trim).filter(_.nonEmpty)
      val lowGap = time - lastTime > 1800 && (ground || lastAlt.isNaN || lastAlt < 10000)
      if ((ground || (flags & 2) != 0 || lowGap) && count > 0) close()
      if (!ground) for {
        la <- lat
        lo <- lon
        a <- alt
      } {
        current += TrackPoint(Instant.ofEpochMilli((time * 1000).toLong), la, lo, math.max(0.0, a))
        count += 1
      }
      // Crews often set the callsign at the gate, so one seen on the ground names the next leg.
      flight.foreach(f => callsign = Some(f.toUpperCase))
      lastTime = time
      lastAlt = if (ground) 0.0 else alt.getOrElse(lastAlt)
    }
    close()
    out.result()
  }

  /**
   * Start times of the half-hour slots to scan so that every flight of the last day shows up in at least one. Over
   * oceans there is no ADS-B coverage, so a long-haul flight is only visible for an hour or two at each end (taxi,
   * climb, descent): slots are hourly, on the hour, so searches for different routes share them. Used only when a
   * route has no readable timetable; timetabled flights are found one slot a day (RouteScheduleService.lastLegAsync).
   * Only hops shorter than about an hour gate to gate can fall between two.
   */
  def slotsToScan(now: Instant = Instant.now()): Seq[Instant] = {
    // The current slot is still being written and the previous one may not be uploaded yet.
    val newest = (now.getEpochSecond - 3600) / 3600 * 3600
    (0 until 24).map(k => Instant.ofEpochSecond(newest - k * 3600))
  }
}
