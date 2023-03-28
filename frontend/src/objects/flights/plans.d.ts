import type {FlightNumber} from "@/objects/flights/shared";

export interface FlightPlanResponse {
    flightPlan: FlightPlan
}
export interface FlightPlan {
    flightNumber: FlightNumber
    // flightRoute: FlightRoute
    waypoints: Waypoint[]
}

export interface Waypoint {
    lat: number
    lon: string
}