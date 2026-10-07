import axios from 'axios'
import type { AircraftClass, AircraftView, TurbulenceCategory } from '@/api/turbulence'

export interface Place {
  code: string
  name: string
  lat: number
  lon: number
}

export interface FlightRoute {
  callsign: string
  iataCallsign?: string
  airline?: string
  origin: Place
  destination: Place
}

export interface FlightLookup {
  query: string
  callsign?: string
  route?: FlightRoute
  live?: AircraftView
}

export interface RoutePoint {
  lat: number
  lon: number
  distanceKm: number
  altitudeFt: number
  minutesFromStart: number
  time: string
  edr?: number
  category: TurbulenceCategory
  source: 'Nowcast' | 'Forecast' | 'Blend' | 'NoData'
  nearGround: boolean
}

export interface Segment {
  category: TurbulenceCategory
  startMinute: number
  endMinute: number
}

export interface RouteSummary {
  verdict: string
  worstCategory: TurbulenceCategory
  maxEdr?: number
  worstMinute?: number
  worstLat?: number
  worstLon?: number
  minutesByCategory: Record<string, number>
  percentByCategory: Record<string, number>
  coveragePercent: number
  notes: string[]
}

export interface RouteAnalysis {
  totalDistanceKm: number
  durationMinutes: number
  start: string
  cruiseAltitudeFt: number
  aircraftClass: AircraftClass
  points: RoutePoint[]
  segments: Segment[]
  summary: RouteSummary
  dataSources: { nowcastValid?: string; forecastIssued?: string; forecastUntil?: string }
}

export interface AnalyzeRequest {
  origin: Place
  destination: Place
  departure?: string
  cruiseAltitudeFt?: number
  aircraftClass?: AircraftClass
}

export interface AirportResult {
  name: string
  city: string
  country: string
  iata: string
  icao: string
  location: { lat: number; lon: number }
}

export async function lookupFlight(q: string): Promise<FlightLookup> {
  return (await axios.get('/api/flights/lookup', { params: { q } })).data
}

export async function analyzeFlight(request: AnalyzeRequest): Promise<RouteAnalysis> {
  return (await axios.post('/api/flights/analyze', request)).data
}

export async function searchAirports(query: string): Promise<AirportResult[]> {
  return (await axios.post('/api/airports/search', { query, limit: 10 })).data.airports
}

export function formatDuration(minutes: number): string {
  const m = Math.round(minutes)
  return m >= 60 ? `${Math.floor(m / 60)}h ${String(m % 60).padStart(2, '0')}m` : `${m} min`
}
