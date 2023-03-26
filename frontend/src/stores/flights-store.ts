import {defineStore} from 'pinia'
import type {FlightNumber, FlightPlan} from '@/objects/flights/flights'
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
            if (this.flightPlansById.has(flightNumber)) {
                return;
            }
            console.log(`Loading flight plan for flight ${flightNumber}`)
            const flightPlan: FlightPlan = await axios.get(`/api/flights/${flightNumber}/plan`)
                .then(
                    (response) => {
                        console.log(`Loaded flight plan for flight ${flightNumber}`)
                        return response.data
                    },
                    (error) => {
                        console.log(`Error loading flight plan: ${error}`)
                        return {
                            flightNumber: "ERROR",
                            waypoints: [{lat: 0, lon: 0}, {lat: 0, lon: 0}]
                        }
                    }
                )

            console.log(`Loaded flight plan for flight ${flightNumber}: ${JSON.stringify(flightPlan)}`)

            this.flightPlansById.set(flightNumber, flightPlan)
        }
    }
})
