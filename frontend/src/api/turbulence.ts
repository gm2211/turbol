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

/** Worst EDR per coarse grid block and level, Light or worse only (see backend VolumeSampler). */
export interface TurbulenceVolume {
  levelsFt: number[]
  step: number
  cellKm: number
  /** Flat, 4 per voxel: lat * 100, lon * 100, index into levelsFt, EDR * 200. */
  voxels: number[]
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
}

export async function fetchVolume(frameId: string, step = 6): Promise<TurbulenceVolume> {
  return (await axios.get(`/api/turbulence/volume/${frameId}`, { params: { step } })).data
}

export async function fetchColumn(frameId: string, lat: number, lon: number): Promise<TurbulenceColumn> {
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
