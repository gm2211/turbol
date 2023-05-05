import { defineStore } from 'pinia'
import type { FlightPlan, FlightPlanResponse } from '@/objects/flights/plans'
import axios from 'axios'
import type { FlightLocator, FlightNumber, FlightRoute } from '@/objects/flights/shared'
import type { FlightSearchRequestByRoute, FlightSearchResponse } from '@/objects/flights/search'
import type {
  Airport,
  AirportSearchRequest,
  AirportSearchResponse,
  ICAOCode
} from '@/objects/airports/airports'

interface AirportsState {
  icaosByQuery: Map<string, ICAOCode[]>
  airportsByICAO: Map<ICAOCode, Airport>
}

export const useAirportsStore = defineStore('airports', {
  state: (): AirportsState => {
    return {
      icaosByQuery: new Map() as Map<string, ICAOCode[]>,
      airportsByICAO: new Map() as Map<ICAOCode, Airport>,
    }
  },
  getters: {
    getAirports: (state: AirportsState) => {
      return (query: string): Airport[] => {
        const icaoCodes = state.icaosByQuery.get(query) || []
        return icaoCodes.map(
          (icaoCode: ICAOCode) => state.airportsByICAO.get(icaoCode) || ({} as Airport)
        )
      }
    },
  },
  actions: {
    async fetchAirports(textQuery: string) {
      if (this.airportsByICAO.has(textQuery)) {
        return
      }
      const searchRequest: AirportSearchRequest = {
        query: textQuery,
        limit: 50
      }
      await axios
        .post(`/api/airports/search`, searchRequest)
        .then((axiosResponse) => {
          const response: AirportSearchResponse = axiosResponse.data
          const airports = response.airports || []

          this.icaosByQuery.set(textQuery, [])

          airports.forEach((airport: Airport) => {
            this.icaosByQuery.get(textQuery)?.push(airport.icao)
            this.airportsByICAO.set(airport.icao, airport)
          })
        })
        .catch((error) => {
          console.log(`Error loading airports: ${error}`)
        })
    }
  }
})
