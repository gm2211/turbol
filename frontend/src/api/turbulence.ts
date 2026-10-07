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

export interface TurbulenceStatus {
  nowcast?: FrameInfo
  forecast: FrameInfo[]
  coverage: { south: number; west: number; north: number; east: number }
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
}

export interface TrafficResponse {
  aircraft: AircraftView[]
  updated: string
  complete: boolean
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

export async function fetchTraffic(bounds: {
  south: number
  west: number
  north: number
  east: number
}): Promise<TrafficResponse> {
  return (await axios.get('/api/live/aircraft', { params: bounds })).data
}

export function tileUrl(frameId: string, levelFt: number): string {
  return `${apiBase()}/api/turbulence/tiles/${frameId}/${levelFt}/{z}/{x}/{y}.png`
}

export function formatUtc(iso: string): string {
  const d = new Date(iso)
  return `${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}Z`
}

export function flightLevel(altitudeFt?: number): string {
  if (altitudeFt === undefined) return '?'
  return altitudeFt >= 18000 ? `FL${Math.round(altitudeFt / 100)}` : `${altitudeFt.toLocaleString()} ft`
}
