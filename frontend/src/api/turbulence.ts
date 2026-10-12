import axios from 'axios'

export type TurbulenceCategory = 'NoData' | 'Smooth' | 'Light' | 'Moderate' | 'Severe' | 'Extreme'
export type AircraftClass = 'Light' | 'Medium' | 'Heavy'

export interface FrameInfo {
  id: string
  kind: 'nowcast' | 'forecast'
  validTime: string
  issued: string
  levelsFt: number[]
}

/** The coarse layer drawn outside GTG's 3 km grid (backend GfsTurbulence). */
export interface GlobalLayerInfo {
  source: string
  method: string
  resolutionKm: number
  issued: string
  validTimes: string[]
}

export interface TurbulenceStatus {
  nowcast?: FrameInfo
  forecast: FrameInfo[]
  /** GTG's bounding box. */
  coverage: { south: number; west: number; north: number; east: number }
  /** GTG's exact edge as [lat, lon] pairs: 3 km inside, the global layer outside. */
  gtgOutline?: [number, number][]
  global?: GlobalLayerInfo
  thresholds: Record<AircraftClass, Thresholds>
}

export interface Thresholds {
  light: number
  moderate: number
  severe: number
  extreme: number
}

export interface AircraftView {
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
  aircraftClass: AircraftClass
  edr?: number
  category: TurbulenceCategory
  /** Epoch ms of the position report (live traffic and lookups). */
  positionTime?: number
}

export interface TrafficResponse {
  aircraft: AircraftView[]
  /** False while the server is still fetching parts of the view: poll again soon. */
  complete: boolean
}

export interface Bounds {
  south: number
  west: number
  north: number
  east: number
}

export const categoryColors: Record<TurbulenceCategory, string> = {
  NoData: '#64748b',
  Smooth: '#16a34a',
  Light: '#eab308',
  Moderate: '#f97316',
  Severe: '#dc2626',
  Extreme: '#9333ea'
}

export const categoryLabels: Record<TurbulenceCategory, string> = {
  NoData: 'No data',
  Smooth: 'Smooth',
  Light: 'Light',
  Moderate: 'Moderate',
  Severe: 'Severe',
  Extreme: 'Extreme'
}

export const apiBase = () => axios.defaults.baseURL ?? ''

export async function fetchStatus(): Promise<TurbulenceStatus> {
  return (await axios.get('/api/turbulence/status')).data
}

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
export async function fetchTraffic(bounds: Bounds): Promise<TrafficResponse> {
  const res: { aircraft: AircraftView[]; complete: boolean; serverTime: number } = (
    await axios.get('/api/live/aircraft', { params: bounds })
  ).data
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

/** One aircraft by ICAO hex, or undefined when it isn't being tracked right now. */
export async function fetchAircraft(hex: string): Promise<AircraftView | undefined> {
  try {
    return (await axios.get(`/api/live/aircraft/${encodeURIComponent(hex)}`)).data
  } catch (e) {
    if (axios.isAxiosError(e) && e.response?.status === 404) return undefined
    throw e
  }
}

/** `frameId` may be "latest" for the current nowcast, which works before the status has loaded. */
export function tileUrl(frameId: string, levelFt: number): string {
  return `${apiBase()}/api/turbulence/tiles/${frameId}/${levelFt}/{z}/{x}/{y}.png`
}

export function formatUtc(iso: string): string {
  const d = new Date(iso)
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}Z`
}

export function flightLevel(altitudeFt?: number): string {
  if (altitudeFt === undefined) return '?'
  return altitudeFt >= 18000
    ? `FL${Math.round(altitudeFt / 100)}`
    : `${altitudeFt.toLocaleString()} ft`
}

/** Worst EDR per coarse grid block and level, Light or worse only (see backend VolumeSampler). */
export interface TurbulenceVolume {
  levelsFt: number[]
  step: number
  cellKm: number
  /** Flat, 4 per voxel: lat * 100, lon * 100, index into levelsFt, EDR * 200. */
  voxels: number[]
  /** The same for the coarse global layer outside GTG, in blocks of globalCellKm. */
  globalLevelsFt?: number[]
  globalCellKm?: number
  globalVoxels?: number[]
}

export interface ColumnLevel {
  levelFt: number
  edr?: number
  category: TurbulenceCategory
}

export interface TurbulenceColumn {
  frameId: string
  lat: number
  lon: number
  levels: ColumnLevel[]
  /** Global: the coarse GFS-derived layer (outside GTG). */
  source: 'Nowcast' | 'Forecast' | 'Global' | 'NoData'
}

export async function fetchColumn(
  frameId: string,
  lat: number,
  lon: number
): Promise<TurbulenceColumn> {
  return (await axios.get(`/api/turbulence/column/${frameId}`, { params: { lat, lon } })).data
}

/** Category for an EDR value with one aircraft class's thresholds. */
export function classify(edr: number, t: Thresholds): TurbulenceCategory {
  if (edr < t.light) return 'Smooth'
  if (edr < t.moderate) return 'Light'
  if (edr < t.severe) return 'Moderate'
  if (edr < t.extreme) return 'Severe'
  return 'Extreme'
}

/** RGBA used for each category on the map overlay (same colours and opacity as the backend TileRenderer). */
export const overlayRgba: Record<TurbulenceCategory, [number, number, number, number]> = {
  NoData: [100, 116, 139, 0],
  Smooth: [34, 197, 94, 36],
  Light: [250, 204, 21, 140],
  Moderate: [249, 115, 22, 179],
  Severe: [220, 38, 38, 204],
  Extreme: [147, 51, 234, 217]
}
