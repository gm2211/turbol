import axios from 'axios'
import { type Thresholds, type TurbulenceCategory, classify } from '@/api/turbulence'
import type { RoutePoint } from '@/api/flights'
import { bearing, destination } from '@/fly/geo'

/**
 * The turbulence around a flight (the backend's RouteCorridor): at each route point, levels above and below and
 * offsets to either side of the track, sampled when the flight passes there. Level 0, offset 0 is the route's own
 * sample, so a line drawn through it always matches the air it flies through.
 */
export interface Corridor {
  /** Altitude shifts from the flight's altitude, lowest first. */
  levelsFt: number[]
  /** Distances right of the track (negative: left), leftmost first. */
  offsetsKm: number[]
  points: number
  /** EDR * 200 by point, then level, then offset; 255 where there's no data. */
  edr: Uint8Array
}

export async function fetchCorridor(points: RoutePoint[]): Promise<Corridor> {
  const body = {
    points: points.map((p) => ({ lat: p.lat, lon: p.lon, altitudeFt: p.altitudeFt, time: p.time }))
  }
  const r = (await axios.post('/api/flights/corridor', body)).data
  const bytes = Uint8Array.from(atob(r.edr), (c) => c.charCodeAt(0))
  return { levelsFt: r.levelsFt, offsetsKm: r.offsetsKm, points: r.points, edr: bytes }
}

export const corridorCategories: TurbulenceCategory[] = [
  'Smooth',
  'Light',
  'Moderate',
  'Severe',
  'Extreme'
]

/** EDR at point `k`, level index `l`, offset index `o`; NaN without data. */
export function corridorEdr(c: Corridor, k: number, l: number, o: number): number {
  const b = c.edr[(k * c.levelsFt.length + l) * c.offsetsKm.length + o]
  return b === undefined || b === 255 ? NaN : b / 200
}

/**
 * Category of a cell as felt by an aircraft with response `factor` (the analysis's classification), as an index
 * into corridorCategories, or -1 without data.
 */
export function corridorCategory(
  c: Corridor,
  k: number,
  l: number,
  o: number,
  factor: number,
  t: Thresholds
): number {
  const edr = corridorEdr(c, k, l, o)
  return Number.isNaN(edr) ? -1 : corridorCategories.indexOf(classify(edr * factor, t))
}

/** Raising or lowering the whole flight, and pushing it sideways off its track. */
export interface WhatIf {
  /** Feet above (negative: below) the planned altitude, at cruise. */
  shiftFt: number
  /** Km right of the track (negative: left), at the middle of the route. */
  offsetKm: number
}

export const noChange: WhatIf = { shiftFt: 0, offsetKm: 0 }

/**
 * Distance over which a change eases in after takeoff and out before landing: the flight still leaves and reaches
 * its airports, and a detour bends away from the track and back.
 */
const easeKm = 300

/** How much of a what-if applies at each point: 0 at the airports, 1 once `easeKm` from both. */
export function easing(points: RoutePoint[]): number[] {
  const total = points[points.length - 1]?.distanceKm ?? 0
  return points.map((p) =>
    Math.min(1, Math.max(0, Math.min(p.distanceKm, total - p.distanceKm) / easeKm))
  )
}

/** One point of a what-if flight: the corridor cell it's in, and where it is. */
export interface WhatIfPoint {
  level: number
  offset: number
  lat: number
  lon: number
  altitudeFt: number
  /** Track direction there, degrees clockwise from north. */
  trackDeg: number
}

/** The what-if flight through the corridor: each point snapped to the nearest corridor cell. */
export function whatIfPath(c: Corridor, points: RoutePoint[], w: WhatIf): WhatIfPoint[] {
  const ease = easing(points)
  const levelStep = c.levelsFt[1]! - c.levelsFt[0]!
  const offsetStep = c.offsetsKm[1]! - c.offsetsKm[0]!
  const l0 = c.levelsFt.indexOf(0)
  const o0 = c.offsetsKm.indexOf(0)
  return points.map((p, k) => {
    const a = points[Math.max(k - 1, 0)]!
    const b = points[Math.min(k + 1, points.length - 1)]!
    const trackDeg = a === b ? 0 : bearing(a.lat, a.lon, b.lat, b.lon)
    const level = clamp(
      l0 + Math.round((w.shiftFt * ease[k]!) / levelStep),
      0,
      c.levelsFt.length - 1
    )
    const offset = clamp(
      o0 + Math.round((w.offsetKm * ease[k]!) / offsetStep),
      0,
      c.offsetsKm.length - 1
    )
    const km = c.offsetsKm[offset]!
    const [lat, lon] = km === 0 ? [p.lat, p.lon] : destination(p.lat, p.lon, trackDeg + 90, km)
    return {
      level,
      offset,
      lat,
      lon,
      altitudeFt: Math.max(0, p.altitudeFt + c.levelsFt[level]!),
      trackDeg
    }
  })
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v))

/** Minutes in each corridorCategories entry (and without data, last) along a path through the corridor. */
export function minutesByCategory(
  c: Corridor,
  points: RoutePoint[],
  path: { level: number; offset: number }[],
  factor: number,
  t: Thresholds
): number[] {
  const out = new Array<number>(corridorCategories.length + 1).fill(0)
  for (let k = 0; k + 1 < points.length; k++) {
    const minutes = points[k + 1]!.minutesFromStart - points[k]!.minutesFromStart
    const cat = corridorCategory(c, k, path[k]!.level, path[k]!.offset, factor, t)
    out[cat < 0 ? corridorCategories.length : cat]! += minutes
  }
  return out
}

/** Minutes at Moderate or worse. */
export const roughMinutes = (minutes: number[]) =>
  minutes.slice(2, corridorCategories.length).reduce((a, b) => a + b, 0)

/**
 * The what-if with the fewest minutes at Moderate or worse, then of Light, preferring the smallest change: every
 * altitude in the corridor, and every detour, alone or together.
 */
export function bestWhatIf(
  c: Corridor,
  points: RoutePoint[],
  factor: number,
  t: Thresholds
): { whatIf: WhatIf; minutes: number[] } {
  let best: { whatIf: WhatIf; minutes: number[]; score: number } | undefined
  for (const shiftFt of c.levelsFt)
    for (const offsetKm of c.offsetsKm) {
      const minutes = minutesByCategory(
        c,
        points,
        whatIfPath(c, points, { shiftFt, offsetKm }),
        factor,
        t
      )
      // Rough minutes dominate; then light ones; then the size of the change (1,000 ft ~ 15 km).
      const score =
        roughMinutes(minutes) * 1e6 +
        minutes[1]! * 1e3 +
        Math.abs(shiftFt) / 1000 +
        Math.abs(offsetKm) / 15
      if (!best || score < best.score) best = { whatIf: { shiftFt, offsetKm }, minutes, score }
    }
  return best!
}
