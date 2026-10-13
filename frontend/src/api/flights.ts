import axios from 'axios'
import type {
  AircraftClass,
  AircraftResponse,
  AircraftView,
  TurbulenceCategory
} from '@/api/turbulence'

export interface Place {
  code: string
  name: string
  lat: number
  lon: number
  /** The ICAO code, when known: the route directory that lists a route's flights uses it. */
  icao?: string
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
  /** How strongly this aircraft feels turbulence; missing from analyses made before it existed. */
  aircraft?: AircraftResponse
  points: RoutePoint[]
  segments: Segment[]
  summary: RouteSummary
  dataSources: {
    nowcastValid?: string
    forecastIssued?: string
    forecastUntil?: string
    /** GFS cycle behind the coarse global layer used outside GTG. */
    globalIssued?: string
    /** Sources still filling in after a server restart: missing data may be on its way. */
    loading?: string[]
  }
  /** Where the line came from: the average of the route's recent tracks, one flight's own track, or a great circle. */
  path?: { kind: PathKind; description: string; flownOn?: string }
  /** The paths it could follow, best first; `path` is the one followed. */
  pathOptions?: PathOption[]
}

export interface AnalyzeRequest {
  origin: Place
  destination: Place
  departure?: string
  cruiseAltitudeFt?: number
  callsign?: string
  /** Follow the leg this airframe flew from that takeoff (a ScheduledFlight's hex and flownTakeoff). */
  flown?: { hex: string; takeoff: string }
  /** The path to follow; the best available one when absent or unavailable. */
  path?: PathKind
  /** The IATA flight number, to find its last track when `flown` doesn't name one. */
  flightNumber?: string
  /** The chosen date (YYYY-MM-DD at the origin), for the route's timetable. */
  day?: string
}

export type PathKind = 'filed' | 'average' | 'flown' | 'greatCircle'

/** A path the analysis could follow; `note` says why it isn't available, or what it is. */
export interface PathOption {
  kind: PathKind
  label: string
  available: boolean
  note?: string
  /** Its tracks are still being looked up: analyzing again shortly may find them. */
  pending?: boolean
}

/**
 * A flight on the route on the chosen day: its published times (timetable) or, when no timetable could be read, its
 * observed takeoff moved to the day (adsb); `hex` and `flownTakeoff` name its most recent leg on the route, if any.
 */
export interface ScheduledFlight {
  callsign: string
  flightNumber?: string
  airline?: string
  departure: string
  arrival: string
  /** "16:40" at the origin, as published. */
  departureLocal?: string
  /** "06:45" at the destination, arrivalDayOffset days later. */
  arrivalLocal?: string
  arrivalDayOffset: number
  codeshares: string[]
  /** When the analysis starts: shortly after the gate time. */
  takeoff: string
  landing: string
  typeCode?: string
  aircraftClass: AircraftClass
  registration?: string
  hex?: string
  flownTakeoff?: string
  source: 'timetable' | 'adsb'
}

export interface RouteSchedule {
  flights: ScheduledFlight[]
  candidates: number
  slotsScanned: number
  note?: string
  source?: 'timetable' | 'adsb'
  /** The day whose timetable was read, when the chosen day is beyond its reach. */
  timetableDay?: string
}

/** Flights on the route on `day` (the origin's date): the published timetable, with ADS-B tracks for their paths. */
export async function fetchSchedule(
  origin: Place,
  destination: Place,
  from: Date,
  until: Date,
  day: string
): Promise<RouteSchedule> {
  return (
    await axios.post(
      '/api/flights/scheduled',
      { origin, destination, from: from.toISOString(), until: until.toISOString(), day },
      { timeout: 180000 }
    )
  ).data
}

export interface AirportResult {
  name: string
  city: string
  country: string
  iata: string
  icao: string
  location: { lat: number; lon: number }
}

/** A flight number on a day, from the airline's published timetable (FlightStats). */
export interface DatedFlight {
  flightNumber: string
  airline: string
  /** IATA codes. */
  origin: string
  destination: string
  /** Scheduled gate departure. */
  departure: string
  arrival?: string
  departureLocal: string
  arrivalLocal: string
  arrivalDayOffset: number
  codeshares: string[]
  equipment?: string
  /** The ATC callsign it flew under recently, when known (EK206 flies as UAE5RQ). */
  callsign?: string
  /** False when it doesn't fly on the chosen day: the times are those of operatingDays, moved to it. */
  scheduledOnDay: boolean
  operatingDays: string[]
}

/** Where and when an IATA flight number flies on `day` (YYYY-MM-DD at the origin); undefined when unknown. */
export async function fetchDatedFlight(q: string, day: string): Promise<DatedFlight | undefined> {
  try {
    return (await axios.get('/api/flights/dated', { params: { q, date: day } })).data
  } catch {
    return undefined
  }
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
  return (
    a && {
      code: a.iata || a.icao,
      name: a.name,
      lat: a.location.lat,
      lon: a.location.lon,
      icao: a.icao || undefined
    }
  )
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

/** The sampled point nearest `minute` into the flight; points are in time order. */
export function pointAt(points: RoutePoint[], minute: number): RoutePoint | undefined {
  let best: RoutePoint | undefined
  for (const p of points) {
    if (!best || Math.abs(p.minutesFromStart - minute) < Math.abs(best.minutesFromStart - minute))
      best = p
    else break
  }
  return best
}

/** An analyzed route handed from Analyze to the live map's 3D view (/map?view=3d&route3d=1). */
export interface Route3D {
  /** e.g. "JFK → MXP · DAL184 · Tue 22:00". */
  label: string
  /** Where the line came from (an average of recent tracks, one flown track, a great circle). */
  path?: string
  start: string
  points: RoutePoint[]
}

const route3dKey = 'turbol.route3d'

export function saveRoute3d(route: Route3D): void {
  try {
    localStorage.setItem(route3dKey, JSON.stringify(route))
  } catch {
    // Storage full or blocked: the 3D view just opens without the route.
  }
}

export function loadRoute3d(): Route3D | undefined {
  try {
    const raw = localStorage.getItem(route3dKey)
    return raw ? (JSON.parse(raw) as Route3D) : undefined
  } catch {
    return undefined
  }
}
