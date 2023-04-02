import type { FlightLocator, FlightRoute } from '@/objects/flights/shared'

export interface FlightSearchRequestByRoute {
  route: FlightRoute
}
export interface FlightSearchResponse {
  flights: FlightLocator[]
}
