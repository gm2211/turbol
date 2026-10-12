/**
 * "How bumpy will this flight be", worked out on the phone: a port of the backend's RouteAnalyzer (and of
 * LiveFlightTracker's next-hour headline) that samples a Flight Pack instead of the server's store, so a downloaded
 * flight gives the same answers as Analyze with no network. Formulas, rounding and wording match the backend;
 * src/fly/__tests__/parity.spec.ts checks the port against results the backend computed.
 */
import type { RouteAnalysis, RoutePoint, RouteSummary, Segment } from '@/api/flights'
import {
  type AircraftClass,
  type Thresholds,
  type TurbulenceCategory,
  classify
} from '@/api/turbulence'
import { destination, greatCircleKm, intermediate, rint } from './geo'
import { nearestIndex } from './lambert'
import type { EdrSample } from './pack'

/** What the analyzer samples: the phone's Flight Pack, or anything that answers like the server's store. */
export interface TurbulenceSource {
  sample(lat: number, lon: number, altitudeFt: number, time: number): EdrSample
  /** Epoch ms, when the source has a nowcast. */
  nowcastValid?: number
  forecastIssued?: number
  forecastUntil?: number
  /** The GFS cycle behind the coarse global layer used outside GTG, when the source has it. */
  globalIssued?: number
}

/** The backend's Thresholds.byClass; a pack's manifest carries the server's current values. */
export const defaultThresholds: Record<AircraftClass, Thresholds> = {
  Light: { light: 0.13 * 0.8, moderate: 0.16 * 0.7, severe: 0.36, extreme: 0.64 },
  Medium: { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 },
  Heavy: { light: 0.17 * 0.8, moderate: 0.24 * 0.7, severe: 0.54, extreme: 0.96 }
}

export const categoryRank: Record<TurbulenceCategory, number> = {
  NoData: -1,
  Smooth: 0,
  Light: 1,
  Moderate: 2,
  Severe: 3,
  Extreme: 4
}

const orderedCategories: TurbulenceCategory[] = [
  'Smooth',
  'Light',
  'Moderate',
  'Severe',
  'Extreme',
  'NoData'
]

export function classifyEdr(edr: number, thresholds: Thresholds): TurbulenceCategory {
  return Number.isNaN(edr) ? 'NoData' : classify(edr, thresholds)
}

export const cruiseSpeedKmh = 830.0
const climbRateFtPerKm = 35000.0 / 150.0 // 150 km to reach FL350
const climbDescentPenaltyH = 20.0 / 60.0 // climbing and descending are slower than cruise
const nearGroundFt = 2000.0

/** Typical cruise altitude for a stage length when the flight doesn't say. */
export function defaultCruiseFt(distanceKm: number): number {
  return distanceKm < 400 ? 24000 : distanceKm < 900 ? 31000 : 35000
}

/** Vertical and speed profile along the route. */
export class Profile {
  private readonly climbKm: number
  private readonly descentKm: number
  private readonly peakFt: number
  private readonly descentStartKm: number

  constructor(
    readonly totalKm: number,
    readonly startAltitudeFt: number,
    readonly cruiseFt: number,
    readonly groundSpeedKmh: number,
    readonly includeClimb: boolean,
    readonly descend = true
  ) {
    this.climbKm = includeClimb
      ? Math.min(totalKm / 2, Math.max(0, cruiseFt - startAltitudeFt) / climbRateFtPerKm)
      : 0
    this.descentKm = descend ? Math.min(totalKm - this.climbKm, cruiseFt / climbRateFtPerKm) : 0
    // A short hop never reaches the nominal cruise altitude.
    this.peakFt = includeClimb
      ? Math.min(cruiseFt, this.climbKm * climbRateFtPerKm + startAltitudeFt)
      : cruiseFt
    this.descentStartKm = totalKm - this.descentKm
  }

  altitudeAt(km: number): number {
    if (km < this.climbKm)
      return this.startAltitudeFt + ((this.peakFt - this.startAltitudeFt) * km) / this.climbKm
    if (this.descend && km > this.descentStartKm && this.descentKm > 0)
      return (this.peakFt * (this.totalKm - km)) / this.descentKm
    return this.peakFt
  }

  /** Minutes from start: cruise speed plus a time penalty that accrues across the climb and descent ramps. */
  minutesAt(km: number): number {
    const base = (km / this.groundSpeedKmh) * 60
    const half = (climbDescentPenaltyH * 60) / 2
    const climbPart = this.climbKm > 0 ? half * Math.min(1.0, km / this.climbKm) : 0.0
    const descentPart =
      this.descentKm > 0 && km > this.descentStartKm
        ? (half * (km - this.descentStartKm)) / this.descentKm
        : 0.0
    return base + climbPart + descentPart
  }
}

/** Minutes after takeoff at each distance along a gate-to-gate flight (the profile analyzeFlight uses). */
export function plannedMinutesAt(totalKm: number, cruiseFt?: number): (km: number) => number {
  const profile = new Profile(
    totalKm,
    0,
    cruiseFt ?? defaultCruiseFt(totalKm),
    cruiseSpeedKmh,
    true
  )
  return (km) => profile.minutesAt(km)
}

export interface AnalyzeOptions {
  thresholds?: Record<AircraftClass, Thresholds>
  /** The clock, for the "beyond the forecast horizon" note. */
  now?: number
}

/** A whole flight, gate to gate, departing at `departure` (epoch ms). */
export function analyzeFlight(
  source: TurbulenceSource,
  fromLat: number,
  fromLon: number,
  toLat: number,
  toLon: number,
  departure: number,
  cruiseFt: number | undefined,
  aircraftClass: AircraftClass,
  options: AnalyzeOptions = {}
): RouteAnalysis {
  const total = greatCircleKm(fromLat, fromLon, toLat, toLon)
  const profile = new Profile(total, 0, cruiseFt ?? defaultCruiseFt(total), cruiseSpeedKmh, true)
  const pointAt = (f: number) => intermediate(fromLat, fromLon, toLat, toLon, f)
  return analyze(source, total, pointAt, profile, departure, aircraftClass, options)
}

/**
 * The rest of a flight that is already airborne: from its position, altitude and ground speed to the destination
 * (descending at the end), or straight ahead along its track when the destination is unknown.
 */
export function analyzeRemaining(
  source: TurbulenceSource,
  lat: number,
  lon: number,
  altitudeFt: number,
  groundSpeedKts: number | undefined,
  trackDeg: number | undefined,
  to: [number, number] | undefined,
  now: number,
  aircraftClass: AircraftClass,
  options: AnalyzeOptions = {},
  lookaheadMinutes = 90
): RouteAnalysis {
  const speedKmh =
    groundSpeedKts !== undefined && groundSpeedKts > 80 ? groundSpeedKts * 1.852 : cruiseSpeedKmh
  if (to) {
    const [toLat, toLon] = to
    const total = greatCircleKm(lat, lon, toLat, toLon)
    const profile = new Profile(total, altitudeFt, altitudeFt, speedKmh, false)
    const pointAt = (f: number) => intermediate(lat, lon, toLat, toLon, f)
    return analyze(source, total, pointAt, profile, now, aircraftClass, options)
  }
  const total = (speedKmh * lookaheadMinutes) / 60
  const track = trackDeg ?? 0.0
  const profile = new Profile(total, altitudeFt, altitudeFt, speedKmh, false, false)
  const pointAt = (f: number) => destination(lat, lon, track, total * f)
  return analyze(source, total, pointAt, profile, now, aircraftClass, options)
}

const iso = (ms: number) => new Date(ms).toISOString()
/** java.time.Instant.toString, for text that quotes a time the way the server does. */
const instantText = (ms: number) => iso(ms).replace('.000Z', 'Z')

function analyze(
  source: TurbulenceSource,
  totalKm: number,
  pointAt: (f: number) => [number, number],
  profile: Profile,
  start: number,
  aircraftClass: AircraftClass,
  options: AnalyzeOptions
): RouteAnalysis {
  const thresholds = (options.thresholds ?? defaultThresholds)[aircraftClass]
  const n = Math.max(40, Math.min(500, Math.trunc(totalKm / 15) + 2))
  const points: RoutePoint[] = []
  const times: number[] = []
  for (let k = 0; k < n; k++) {
    const f = n === 1 ? 0.0 : k / (n - 1)
    const km = totalKm * f
    const [lat, lon] = pointAt(f)
    const alt = profile.altitudeAt(km)
    const minutes = profile.minutesAt(km)
    const time = start + Math.trunc(minutes * 60) * 1000
    const sample = source.sample(lat, lon, alt, time)
    times.push(time)
    points.push({
      lat,
      lon,
      distanceKm: km,
      altitudeFt: rint(alt),
      minutesFromStart: minutes,
      time: iso(time),
      edr: Number.isNaN(sample.edr) ? undefined : sample.edr,
      category: classifyEdr(sample.edr, thresholds),
      source: sample.source,
      nearGround: alt < nearGroundFt
    })
  }
  return {
    totalDistanceKm: totalKm,
    durationMinutes: points[points.length - 1]?.minutesFromStart ?? 0.0,
    start: iso(start),
    cruiseAltitudeFt: profile.cruiseFt,
    aircraftClass,
    points,
    segments: segments(points),
    summary: summarize(points, times, source, start, options.now ?? Date.now()),
    dataSources: {
      nowcastValid: source.nowcastValid === undefined ? undefined : iso(source.nowcastValid),
      forecastIssued: source.forecastIssued === undefined ? undefined : iso(source.forecastIssued),
      forecastUntil: source.forecastUntil === undefined ? undefined : iso(source.forecastUntil),
      globalIssued: source.globalIssued === undefined ? undefined : iso(source.globalIssued)
    }
  }
}

function segments(points: RoutePoint[]): Segment[] {
  const out: Segment[] = []
  for (const p of points) {
    const last = out[out.length - 1]
    if (last) last.endMinute = p.minutesFromStart
    if (!last || last.category !== p.category)
      out.push({
        category: p.category,
        startMinute: p.minutesFromStart,
        endMinute: p.minutesFromStart
      })
  }
  return out
}

export function fmtMinutes(m: number): string {
  const total = rint(m)
  return total >= 60
    ? `${Math.trunc(total / 60)}h${String(total % 60).padStart(2, '0')}m`
    : `${total} min`
}

function summarize(
  points: RoutePoint[],
  times: number[],
  source: TurbulenceSource,
  start: number,
  now: number
): RouteSummary {
  const enRoute = points.filter((p) => !p.nearGround)
  const step =
    points.length > 1 ? points[points.length - 1].minutesFromStart / (points.length - 1) : 0.0
  const withData = enRoute.filter((p) => p.edr !== undefined)
  const counts = new Map(
    orderedCategories.map((c) => [c, enRoute.filter((p) => p.category === c).length])
  )
  // The first point with the highest EDR, as Scala's maxBy.
  const worst = withData.reduce<RoutePoint | undefined>(
    (best, p) => (best === undefined || (p.edr ?? 0) > (best.edr ?? 0) ? p : best),
    undefined
  )
  const worstCategory: TurbulenceCategory = worst?.category ?? 'NoData'
  const coverage = enRoute.length === 0 ? 0.0 : (100.0 * withData.length) / enRoute.length

  const percentOfRoute = (k: number) => ((100.0 * k) / enRoute.length).toFixed(0)
  const notes: string[] = []
  const global = enRoute.filter((p) => p.source === 'Global').length
  if (global > 0)
    notes.push(
      `${percentOfRoute(global)}% of the route is outside GTG coverage (contiguous US and nearby) and uses ` +
        "the coarser global forecast (WAFS and NOAA GFS, about 28 km), which is less precise than GTG's 3 km."
    )
  if (coverage < 99.5) {
    const outside = enRoute.filter(
      (p) => p.edr === undefined && nearestIndex(p.lat, p.lon) < 0
    ).length
    if (outside > 0)
      notes.push(
        `${percentOfRoute(outside)}% of the route is outside GTG coverage (contiguous US and nearby)` +
          ' and the global forecast has no data for it yet.'
      )
    const until = source.forecastUntil
    if (until !== undefined && points.some((p, k) => times[k] > until && p.edr === undefined))
      notes.push(
        `The forecast only reaches ${instantText(until)} (18 h after the latest GTG cycle); later parts of the flight have no data yet.`
      )
    if (source.forecastUntil === undefined && source.nowcastValid === undefined)
      notes.push('Turbulence data is still loading.')
  }
  if (Math.trunc(Math.floor((start - now) / 1000) / 3600) > 18)
    notes.push(
      'This departure is beyond the 18-hour GTG forecast horizon. Check back closer to the day.'
    )

  let verdict: string
  if (withData.length === 0) verdict = 'No turbulence data for this route yet.'
  else {
    const at = worst ? fmtMinutes(worst.minutesFromStart) : ''
    switch (worstCategory) {
      case 'Extreme':
        verdict =
          'Expect a rough ride: extreme turbulence is forecast on this route, and airlines would route around it.'
        break
      case 'Severe':
        verdict = `Buckle up: severe turbulence is forecast around ${at} into the flight. Expect a bumpy ride or a reroute.`
        break
      case 'Moderate':
        verdict = `Some bumps likely, worst around ${at} into the flight. Keep your seatbelt fastened.`
        break
      case 'Light':
        verdict = 'Mostly smooth, with occasional light bumps. Nothing to worry about.'
        break
      default:
        verdict = 'Smooth ride: no significant turbulence expected.'
    }
  }

  const minutesByCategory: Record<string, number> = {}
  const percentByCategory: Record<string, number> = {}
  for (const [c, k] of counts) {
    minutesByCategory[c] = rint(k * step * 10) / 10
    percentByCategory[c] = enRoute.length === 0 ? 0.0 : rint((1000.0 * k) / enRoute.length) / 10
  }
  return {
    verdict,
    worstCategory,
    maxEdr: worst?.edr,
    worstMinute: worst?.minutesFromStart,
    worstLat: worst?.lat,
    worstLon: worst?.lon,
    minutesByCategory,
    percentByCategory,
    coveragePercent: rint(coverage * 10) / 10,
    notes
  }
}

/** What's coming up in the next hour, in passenger terms (the backend's LiveFlightTracker.upcoming). */
export interface Upcoming {
  headline: string
  nowCategory: TurbulenceCategory
  nextBumpCategory?: TurbulenceCategory
  nextBumpInMinutes?: number
  nextBumpForMinutes?: number
  worstNextHour: TurbulenceCategory
}

const horizonMinutes = 60.0

export function upcoming(
  ahead: RouteAnalysis,
  edrNow: number | undefined,
  aircraftClass: AircraftClass,
  thresholds: Record<AircraftClass, Thresholds> = defaultThresholds
): Upcoming {
  const nowCategory = classifyEdr(edrNow ?? NaN, thresholds[aircraftClass])
  const window = ahead.points.filter((p) => p.minutesFromStart <= horizonMinutes && !p.nearGround)
  const highest = (cs: TurbulenceCategory[]) =>
    cs.reduce<TurbulenceCategory | undefined>(
      (best, c) => (best === undefined || categoryRank[c] > categoryRank[best] ? c : best),
      undefined
    )
  const worst = highest(window.map((p) => p.category)) ?? 'NoData'
  const bumpy = (p: RoutePoint) => categoryRank[p.category] >= categoryRank.Light
  const firstBump = window.find((p) => bumpy(p) && p.minutesFromStart > 0.5)
  const bumpEnd = firstBump
    ? window.find((p) => p.minutesFromStart > firstBump.minutesFromStart && !bumpy(p))
    : undefined
  const lasting =
    firstBump && bumpEnd ? bumpEnd.minutesFromStart - firstBump.minutesFromStart : undefined
  const worstInBump = firstBump
    ? highest(
        window
          .filter(
            (p) =>
              p.minutesFromStart >= firstBump.minutesFromStart &&
              (bumpEnd === undefined || p.minutesFromStart < bumpEnd.minutesFromStart)
          )
          .map((p) => p.category)
      )
    : undefined

  let headline: string
  if (nowCategory === 'NoData' && !firstBump && worst === 'NoData')
    headline = 'No turbulence data here right now.'
  else if (categoryRank[nowCategory] >= categoryRank.Light)
    headline = `${nowCategory} turbulence right now.`
  else if (firstBump && worstInBump) {
    const inMin = Math.max(1, rint(firstBump.minutesFromStart))
    const forMin = lasting === undefined ? '' : ` for about ${Math.max(1, rint(lasting))} min`
    const label = worstInBump.toLowerCase()
    headline = `Smooth now. ${label.charAt(0).toUpperCase() + label.slice(1)} bumps expected in about ${inMin} min${forMin}.`
  } else headline = 'Smooth now, and smooth for the next hour.'

  return {
    headline,
    nowCategory,
    nextBumpCategory: worstInBump,
    nextBumpInMinutes: firstBump?.minutesFromStart,
    nextBumpForMinutes: lasting,
    worstNextHour: worst
  }
}
