/**
 * A flight followed on the live map, made into one to download for offline use. ADS-B carries no schedule, so the
 * departure is worked back from where the plane is: the takeoff the planned profile needs to have flown it there by
 * now, less the usual taxi. Where the plane is goes in as a position fix, the way a GPS fix does, so the downloaded
 * flight starts from where the plane really is rather than from that worked-back schedule.
 */
import type { LiveFlightUpdate } from '@/api/follow'
import { type GpsFix, type SavedFlight, newFlight } from './flights'
import { greatCircleKm } from './geo'
import { flightProfile, taxiOutMinutes, withFix } from './position'

/** Above this (ft) and nearly level, a plane is cruising: its altitude is the flight's cruise. */
const cruiseFloorFt = 25000

export function flightFromLive(u: LiveFlightUpdate, now: number): SavedFlight | undefined {
  const a = u.aircraft
  const r = u.route
  if (!a || !r || a.onGround) return undefined
  const seen = Date.parse(u.time)
  const time = Number.isFinite(seen) ? Math.min(seen, now) : now
  const level =
    a.altitudeFt !== undefined &&
    a.altitudeFt >= cruiseFloorFt &&
    Math.abs(a.verticalRateFpm ?? 0) < 500
  const fields = {
    label: r.iataCallsign ?? r.callsign,
    callsign: a.callsign ?? r.callsign,
    origin: r.origin,
    destination: r.destination,
    aircraftClass: u.aircraftClass,
    aircraftType: a.typeCode,
    aircraft: u.aircraftResponse,
    cruiseFt: level ? Math.round(a.altitudeFt! / 1000) * 1000 : undefined
  }
  const profile = flightProfile(newFlight({ ...fields, departure: time }))
  const toGo = greatCircleKm(a.lat, a.lon, r.destination.lat, r.destination.lon)
  const takeoff = time - profile.minutesAt(Math.max(0, profile.totalKm - toGo)) * 60_000
  const departure = Math.round(takeoff / 60_000 - taxiOutMinutes) * 60_000
  const flight = newFlight({ ...fields, departure })
  const fix: GpsFix = {
    time,
    lat: a.lat,
    lon: a.lon,
    speedMps: a.groundSpeedKts === undefined ? undefined : (a.groundSpeedKts * 1852) / 3600,
    source: 'adsb'
  }
  return withFix(flight, fix) ?? flight
}

/** Whether two flights are the same trip: one route, leaving within a few hours of each other. */
export const sameFlight = (a: SavedFlight, b: SavedFlight) =>
  a.origin.code === b.origin.code &&
  a.destination.code === b.destination.code &&
  Math.abs(a.departure - b.departure) < 3 * 3600_000
