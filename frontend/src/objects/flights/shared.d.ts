type FlightNumber = string

export interface FlightLocator {
    flightNumber: FlightNumber
    departureDateTime: Date
}

export interface FlightRoute {
    sourceAirport: string
    destinationAirport: string
}
