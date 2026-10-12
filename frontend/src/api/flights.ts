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
  /** Airborne now, flying this route. */
  live?: AircraftView
  /** Airborne now under this callsign, but on a different leg (airlines reuse flight numbers for every leg). */
  otherLeg?: AircraftView
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
  source: 'Nowcast' | 'Forecast' | 'Blend' | 'Global' | 'NoData'
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
  aircraftType?: string
  points: RoutePoint[]
  segments: Segment[]
  summary: RouteSummary
  dataSources: {
    nowcastValid?: string
    forecastIssued?: string
    forecastUntil?: string
    /** GFS cycle behind the coarse global layer used outside GTG. */
    globalIssued?: string
  }
}

export interface AnalyzeRequest {
  origin: Place
  destination: Place
  departure?: string
  cruiseAltitudeFt?: number
  callsign?: string
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

/** An aircraft in the air now flying the searched route. */
export interface RouteFlight {
  callsign: string
  minutesToGo?: number
  aircraft: AircraftView
}

/** Flights in the air now from origin to destination. */
export async function searchRoute(origin: Place, destination: Place): Promise<RouteFlight[]> {
  return (await axios.post('/api/flights/route', { origin, destination })).data.flights
}

/** "JFK LAX", "jfk-lax", "KJFK to KLAX", "JFK→LAX": the two airport codes, or undefined for anything else. */
export function parseRouteQuery(q: string): [string, string] | undefined {
  const m = q
    .trim()
    .toUpperCase()
    .match(/^([A-Z]{3,4})\s*(?:-|–|>|→|\s|\s+TO\s+)\s*([A-Z]{3,4})$/)
  return m ? [m[1]!, m[2]!] : undefined
}

/** The airport with this IATA or ICAO code (else the best search hit), as a route endpoint. */
export async function airportByCode(code: string): Promise<Place | undefined> {
  const airports = await searchAirports(code)
  const a =
    airports.find((x) => x.iata === code.toUpperCase() || x.icao === code.toUpperCase()) ??
    airports[0]
  return a && { code: a.iata || a.icao, name: a.name, lat: a.location.lat, lon: a.location.lon }
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
