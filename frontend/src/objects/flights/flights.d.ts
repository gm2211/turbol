type FlightNumber = string

export interface FlightPlan {
    flightNumber: FlightNumber
    waypoints: Waypoint[]
}

export interface Waypoint {
    lat: number
    lon: string
}