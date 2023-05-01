import type { GPSLocation } from '@/objects/location/location'

export type IATACode = string
export type ICAOCode = string

interface Airport {
  name: string
  city: string
  country: string
  iata: IATACode
  icao: ICAOCode
  location: GPSLocation
}

interface AirportSearchRequest {
  query: string,
  limit: number
}

interface AirportSearchResponse {
  airports: Airport[]
}
