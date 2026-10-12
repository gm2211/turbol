// Live-traffic processing shared by the page and the traffic worker (no imports with side effects: it runs in both).
import type { AircraftView, Bounds, TrafficResponse } from '@/api/turbulence'

/** What /api/live/aircraft returns. */
export interface TrafficReply {
  aircraft: AircraftView[]
  complete: boolean
  serverTime: number
}

export interface TrafficWorkerRequest {
  id: number
  url: string
  bounds: Bounds
}

/** One batch of a reply's aircraft; the last has done set. error replaces everything when the fetch failed. */
export type TrafficWorkerReply =
  | { id: number; error?: undefined; aircraft: AircraftView[]; done: boolean; complete: boolean }
  | { id: number; error: string }

/** Where an aircraft is now, dead-reckoned along its track from a report `ageSeconds` old. */
export function deadReckon(a: AircraftView, ageSeconds: number): AircraftView {
  if (
    a.onGround ||
    a.groundSpeedKts == null ||
    a.trackDeg == null ||
    ageSeconds <= 1 ||
    ageSeconds >= 300
  )
    return a
  const R = 6371
  const d = (a.groundSpeedKts * 1.852 * ageSeconds) / 3600 / R
  const brg = (a.trackDeg * Math.PI) / 180
  const lat1 = (a.lat * Math.PI) / 180
  const lon1 = (a.lon * Math.PI) / 180
  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brg)
  )
  const lon2 =
    lon1 +
    Math.atan2(
      Math.sin(brg) * Math.sin(d) * Math.cos(lat1),
      Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
    )
  return { ...a, lat: (lat2 * 180) / Math.PI, lon: (((lon2 * 180) / Math.PI + 540) % 360) - 180 }
}
/**
 * Airborne aircraft in a viewport, positioned for now. The server sends whole cached cells (which overlap and spill
 * past the viewport) with report times, so this dedupes, dead-reckons and crops.
 */
export function cropTraffic(res: TrafficReply, bounds: Bounds): TrafficResponse {
  const reported = (a: AircraftView) => a.positionTime ?? res.serverTime
  const newest = new Map<string, AircraftView>()
  for (const a of res.aircraft) {
    const seen = newest.get(a.hex)
    if (!seen || reported(a) > reported(seen)) newest.set(a.hex, a)
  }
  const aircraft = [...newest.values()]
    .map((a) => deadReckon(a, (res.serverTime - reported(a)) / 1000))
    .filter(
      (a) =>
        a.lat >= bounds.south &&
        a.lat <= bounds.north &&
        a.lon >= bounds.west &&
        a.lon <= bounds.east
    )
  return { aircraft, complete: res.complete }
}
