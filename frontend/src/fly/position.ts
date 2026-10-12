/**
 * Where the plane is, from the phone alone. With no GPS fix in the air, the planned profile is flown from the takeoff:
 * the passenger's "We've taken off" tap, GPS on the takeoff run, or else the schedule. A GPS fix pins the plan's clock
 * to where the plane really was; one in cruise also sets its pace from the fix's ground speed (a tailwind makes the
 * rest of the flight shorter), and the position is carried on from the fix toward the destination and down the
 * planned descent. Each estimate says how far off it can be: the 90th-percentile timing error of dead reckoning,
 * from the design doc's replay of real flights (§5): no fix since takeoff, or minutes since the last cruise fix.
 */
import { Profile, cruiseSpeedKmh, defaultCruiseFt } from './analyzer'
import type { GpsFix, SavedFlight } from './flights'
import { bearing, greatCircleKm, intermediate } from './geo'

/** Assumed minutes from the scheduled gate departure to takeoff, until the passenger or GPS says otherwise. */
export const taxiOutMinutes = 15
/** Faster than this (about 100 knots) the plane is on its takeoff run or flying. */
export const airborneSpeedMps = 50
/** No airliner covers the ground faster than this (about 780 knots): a faster "speed" comes from a bad fix. */
export const maxGroundSpeedMps = 400
/** A fix less sure than this (meters) comes from Wi-Fi or cell towers, which on a plane can put it anywhere. */
export const maxAccuracyM = 1000
/** A fix at flying speed this close to the origin (km) is the takeoff run or the first minutes of the climb. */
const takeoffRadiusKm = 30
/** A fix at least this fast (about 290 knots) where the plan cruises sets the pace for the rest of the flight. */
const cruiseFixSpeedMps = 150
/** The pace a cruise fix may set: ground speeds from a strong jet-stream tailwind to a strong headwind. */
const paceRange: [number, number] = [0.65, 1.4]
/** The schedule alone also misses the real takeoff time by this much (p90), on top of the in-flight drift. */
const scheduleSlackMinutes = 10

/** [minutes since takeoff, p90 minutes] with no position fix since takeoff. */
const noFixP90: [number, number][] = [
  [0, 0],
  [30, 6.6],
  [90, 13.4],
  [150, 23.7],
  [210, 26.5],
  [270, 26.5]
]
/** [minutes since a GPS or ADS-B fix in cruise, p90 minutes]. */
const sinceFixP90: [number, number][] = [
  [0, 0],
  [1, 0.04],
  [2, 0.08],
  [5, 0.21],
  [10, 0.48],
  [15, 0.92],
  [20, 1.54],
  [30, 2.53],
  [45, 3.4],
  [60, 4.18],
  [90, 5.69]
]

function lookup(table: [number, number][], x: number): number {
  if (x <= table[0][0]) return table[0][1]
  for (let k = 1; k < table.length; k++) {
    const [x1, y1] = table[k]
    const [x0, y0] = table[k - 1]
    if (x <= x1) return y0 + ((y1 - y0) * (x - x0)) / (x1 - x0)
  }
  // Past the table: keep its last slope, as drift keeps growing.
  const [xa, ya] = table[table.length - 2]
  const [xb, yb] = table[table.length - 1]
  return yb + ((yb - ya) * (x - xb)) / (xb - xa)
}

export type Basis = 'schedule' | 'tap' | 'gps-takeoff' | 'gps'

export interface Estimate {
  phase: 'gate' | 'air' | 'landed'
  /** Takeoff as this estimate has it (epoch ms). */
  takeoff: number
  /** Landing as this estimate has it (epoch ms). */
  landing: number
  /** Real minutes since takeoff. */
  minutesIntoFlight: number
  /** Real minutes until landing. */
  minutesToGo: number
  /**
   * Where the plane is on the plan's clock: the minute after takeoff at which the planned flight reaches it. The
   * route analysis counts minutes on this clock.
   */
  plannedMinute: number
  /** When the plan's clock read 0 (epoch ms). It holds still between GPS fixes, so the analysis can start there. */
  planStart: number
  /** Real minutes per planned minute from here to landing: below 1 with a tailwind. */
  pace: number
  lat: number
  lon: number
  altitudeFt: number
  trackDeg: number
  /** From GPS, when the estimate starts from a fix that has it. */
  groundSpeedKts?: number
  basis: Basis
  /** The estimate is within this many minutes of the truth 9 times in 10. */
  uncertaintyMinutes: number
  /** Minutes since the GPS fix the estimate starts from. */
  fixAgeMinutes?: number
}

/** The planned gate-to-gate profile, as the route analyzer flies it. */
export function flightProfile(f: SavedFlight): Profile {
  const total = greatCircleKm(f.origin.lat, f.origin.lon, f.destination.lat, f.destination.lon)
  return new Profile(total, 0, f.cruiseFt ?? defaultCruiseFt(total), cruiseSpeedKmh, true)
}

/** Distance along the route reached `minutes` after takeoff (the inverse of Profile.minutesAt). */
export function kmAtMinutes(profile: Profile, minutes: number): number {
  if (minutes <= 0) return 0
  if (minutes >= profile.minutesAt(profile.totalKm)) return profile.totalKm
  let [lo, hi] = [0, profile.totalKm]
  for (let k = 0; k < 50; k++) {
    const mid = (lo + hi) / 2
    if (profile.minutesAt(mid) < minutes) lo = mid
    else hi = mid
  }
  return (lo + hi) / 2
}

/** Takeoff time from the passenger or GPS, else the schedule. */
export function takeoffOf(f: SavedFlight): { time: number; basis: Basis } {
  if (f.takeoff)
    return { time: f.takeoff.time, basis: f.takeoff.source === 'gps' ? 'gps-takeoff' : 'tap' }
  return { time: f.departure + taxiOutMinutes * 60_000, basis: 'schedule' }
}

/** Whether a GPS fix shows the plane moving at flying speed (and a speed a plane can have). */
export const isAirborne = (fix: GpsFix) =>
  fix.speedMps !== undefined &&
  fix.speedMps >= airborneSpeedMps &&
  fix.speedMps <= maxGroundSpeedMps

const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, x))

export function estimate(f: SavedFlight, now: number): Estimate {
  const profile = flightProfile(f)
  const planned = profile.minutesAt(profile.totalKm)
  const toDestination = (lat: number, lon: number) =>
    greatCircleKm(lat, lon, f.destination.lat, f.destination.lon)
  const fix = f.airborneFix && f.airborneFix.time <= now ? f.airborneFix : undefined

  if (fix) {
    // Pin the plan's clock to the fix: where the plan puts that distance flown, the plane was at the fix's time.
    const toGo = toDestination(fix.lat, fix.lon)
    const flownKm = Math.max(0, profile.totalKm - toGo)
    const fixMinute = profile.minutesAt(flownKm)
    const planStart = fix.time - fixMinute * 60_000
    const cruising =
      (fix.speedMps ?? 0) >= cruiseFixSpeedMps &&
      profile.altitudeAt(flownKm) >= 0.95 * profile.cruiseFt
    const pace = cruising
      ? clamp(cruiseSpeedKmh / (fix.speedMps! * 3.6), paceRange[0], paceRange[1])
      : 1
    const age = (now - fix.time) / 60_000
    const plannedMinute = Math.min(planned, fixMinute + age / pace)
    // Carried on from the fix toward the destination, as far as the plan goes in that time.
    const moved = Math.min(toGo, kmAtMinutes(profile, plannedMinute) - flownKm)
    const [lat, lon] =
      toGo === 0
        ? [fix.lat, fix.lon]
        : intermediate(fix.lat, fix.lon, f.destination.lat, f.destination.lon, moved / toGo)
    const takeoff = f.takeoff?.source === 'gps' ? f.takeoff.time : planStart
    const landing = fix.time + (planned - fixMinute) * pace * 60_000
    return {
      phase: now >= landing ? 'landed' : 'air',
      takeoff,
      landing,
      minutesIntoFlight: (now - takeoff) / 60_000,
      minutesToGo: Math.max(0, (landing - now) / 60_000),
      plannedMinute,
      planStart,
      pace,
      lat,
      lon,
      altitudeFt: profile.altitudeAt(Math.min(profile.totalKm, flownKm + moved)),
      trackDeg:
        toDestination(lat, lon) < 1
          ? bearing(fix.lat, fix.lon, f.destination.lat, f.destination.lon)
          : bearing(lat, lon, f.destination.lat, f.destination.lon),
      groundSpeedKts: fix.speedMps !== undefined ? (fix.speedMps * 3600) / 1852 : undefined,
      basis: 'gps',
      // A cruise fix carried on at its own speed drifts slowly; any other fix only pins the clock.
      uncertaintyMinutes: lookup(cruising ? sinceFixP90 : noFixP90, age),
      fixAgeMinutes: age
    }
  }

  const takeoff = takeoffOf(f)
  const minutes = (now - takeoff.time) / 60_000
  const km = kmAtMinutes(profile, minutes)
  const route = (d: number): [number, number] =>
    intermediate(
      f.origin.lat,
      f.origin.lon,
      f.destination.lat,
      f.destination.lon,
      profile.totalKm === 0 ? 0 : d / profile.totalKm
    )
  const [lat, lon] = route(km)
  const ahead = route(Math.min(profile.totalKm, km + 1))
  const drift = lookup(noFixP90, Math.max(0, minutes))
  const landing = takeoff.time + planned * 60_000
  return {
    phase: minutes < 0 ? 'gate' : now >= landing ? 'landed' : 'air',
    takeoff: takeoff.time,
    landing,
    minutesIntoFlight: minutes,
    minutesToGo: Math.max(0, (landing - now) / 60_000),
    plannedMinute: clamp(minutes, 0, planned),
    planStart: takeoff.time,
    pace: 1,
    lat,
    lon,
    altitudeFt: profile.altitudeAt(km),
    trackDeg:
      km >= profile.totalKm
        ? bearing(f.origin.lat, f.origin.lon, f.destination.lat, f.destination.lon)
        : bearing(lat, lon, ahead[0], ahead[1]),
    basis: takeoff.basis,
    uncertaintyMinutes: takeoff.basis === 'schedule' ? drift + scheduleSlackMinutes : drift
  }
}

/**
 * What a new GPS fix changes: the fix the estimate starts from while airborne and, near the origin, the takeoff.
 * Returns undefined when nothing changes: the phone isn't moving at flying speed, the fix is vague (Wi-Fi or cell
 * positioning) or far off the route, or it's no newer than the one kept.
 */
export function withFix(f: SavedFlight, fix: GpsFix): SavedFlight | undefined {
  if (!isAirborne(fix) || (fix.accuracyM ?? 0) > maxAccuracyM) return undefined
  if (f.airborneFix && f.airborneFix.time >= fix.time) return undefined
  // A fix far from the planned route belongs to another flight (or a bad reading): keep the schedule.
  const total = greatCircleKm(f.origin.lat, f.origin.lon, f.destination.lat, f.destination.lon)
  const fromOrigin = greatCircleKm(f.origin.lat, f.origin.lon, fix.lat, fix.lon)
  const detour =
    fromOrigin + greatCircleKm(fix.lat, fix.lon, f.destination.lat, f.destination.lon) - total
  if (detour > 600) return undefined
  // At flying speed near the origin: the takeoff run or the climb out, which times the takeoff better than a tap.
  const takeoff =
    f.takeoff?.source !== 'gps' && fromOrigin <= takeoffRadiusKm
      ? {
          time: fix.time - flightProfile(f).minutesAt(fromOrigin) * 60_000,
          source: 'gps' as const
        }
      : f.takeoff
  return { ...f, takeoff, airborneFix: fix }
}
