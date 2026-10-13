import type { FlightRoute, RouteAnalysis } from '@/api/flights'
import {
  type AircraftClass,
  type AircraftResponse,
  type TurbulenceCategory,
  apiBase
} from '@/api/turbulence'

export interface LiveAircraft {
  hex: string
  callsign?: string
  registration?: string
  typeCode?: string
  lat: number
  lon: number
  altitudeFt?: number
  onGround: boolean
  groundSpeedKts?: number
  trackDeg?: number
  verticalRateFpm?: number
}

export interface LiveFlightUpdate {
  time: string
  status: 'tracking' | 'lost' | 'on-ground' | 'error'
  aircraft?: LiveAircraft
  aircraftClass: AircraftClass
  aircraftResponse?: AircraftResponse
  edrNow?: number
  route?: FlightRoute
  usingRoute: boolean
  upcoming?: {
    headline: string
    nowCategory: TurbulenceCategory
    worstNextHour: TurbulenceCategory
    nextBumpInMinutes?: number
  }
  ahead?: RouteAnalysis
}

/**
 * The server sends `ahead.points` (most of an update's bytes) only when the analysis changed, and an empty list
 * otherwise. This gives every update the last points it was sent.
 */
export function keepAheadPoints() {
  let last: RouteAnalysis['points'] | undefined
  return (u: LiveFlightUpdate): LiveFlightUpdate => {
    if (!u.ahead) return u
    if (u.ahead.points?.length) last = u.ahead.points
    else if (last) u.ahead.points = last
    return u
  }
}

/** Streams an update about one airborne aircraft every few seconds. Returns a function that closes the stream. */
export function followFlight(hex: string, onUpdate: (u: LiveFlightUpdate) => void): () => void {
  const source = new EventSource(`${apiBase()}/api/live/flights/${encodeURIComponent(hex)}/stream`)
  const withPoints = keepAheadPoints()
  source.onmessage = (event) => {
    const u = JSON.parse(event.data) as LiveFlightUpdate
    if (u.status === 'error') return
    onUpdate(withPoints(u))
  }
  return () => source.close()
}
