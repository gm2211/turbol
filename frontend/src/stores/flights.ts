import { defineStore } from 'pinia'
import axios from 'axios'

interface FlightsState {
  flightPlansById: Map<FlightNumber, FlightPlan>
}

export const useFlightsStore = defineStore('flights', {
  state: (): FlightsState => {
    return {
      flightPlansById: new Map() as Map<FlightNumber, FlightPlan>
    }
  },
  getters: {
    getFlightPlan: (state: FlightsState) => {
      return (flightNumber: FlightNumber): FlightPlan | undefined => {
        return state.flightPlansById.get(flightNumber)
      }
    }
  },
  actions: {
    async loadIfNotPresent(flightNumber: FlightNumber) {
      const response = await axios.get(`/api/flights/${flightNumber}/plan`)
      const flightPlan: FlightPlan = response.data

      this.flightPlansById.set(flightPlan.flightNumber, flightPlan)
    }
  }
})

type FlightNumber = string

interface FlightPlan {
  flightNumber: FlightNumber
  waypoints: Waypoint[]
}

interface Waypoint {
  lat: number
  lon: string
}
