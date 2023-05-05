import {defineStore} from 'pinia'
import type {FlightPlan, FlightPlanResponse} from '@/objects/flights/plans'
import axios from 'axios'
import type {FlightLocator, FlightNumber, FlightRoute} from '@/objects/flights/shared'
import type {FlightSearchRequestByRoute, FlightSearchResponse} from '@/objects/flights/search'

interface FlightsState {
    flightsByRoute: Map<FlightRoute, FlightLocator[]>
    flightPlansById: Map<FlightNumber, FlightPlan>
}

export const useFlightsStore = defineStore('flights', {
    state: (): FlightsState => {
        return {
            flightsByRoute: new Map() as Map<FlightRoute, FlightLocator[]>,
            flightPlansById: new Map() as Map<FlightNumber, FlightPlan>
        }
    },
    getters: {
        getFlightsByRoute: (state: FlightsState) => {
            return (route: FlightRoute): FlightLocator[] => {
                return state.flightsByRoute.get(route) || []
            }
        },
        getFlightPlan: (state: FlightsState) => {
            return (flightNumber: FlightNumber): FlightPlan | undefined => {
                return state.flightPlansById.get(flightNumber)
            }
        }
    },
    actions: {
        async fetchFlightsForRoute(route: FlightRoute) {
            if (this.flightsByRoute.has(route)) {
                return
            }
            const searchRequest: FlightSearchRequestByRoute = {
                route: route
            }
            await axios
                .post(`/api/flights/search/by-route`, searchRequest)
                .then((axiosResponse) => {
                    const response: FlightSearchResponse = axiosResponse.data
                    console.log(`Got response for route ${route}`)
                    this.flightsByRoute.set(route, response.flights)
                })
                .catch((error) => {
                    console.log(`Error loading flights for route: ${error}`)
                })
        },
        async fetchPlanForFlight(flightNumber: FlightNumber) {
            if (this.flightPlansById.has(flightNumber)) {
                return
            }
            console.log(`Loading flight plan for flight ${flightNumber}`)
            await axios.get(`/api/flights/${flightNumber}/plan`).then(
                (axiosResponse) => {
                    const response: FlightPlanResponse = axiosResponse.data
                    console.log(`Loaded flight plan for flight ${flightNumber}`)
                    this.flightPlansById.set(flightNumber, response.flightPlan)
                },
                (error) => {
                    console.log(`Error loading flight plan: ${error}`)
                }
            )
        }
    }
})
