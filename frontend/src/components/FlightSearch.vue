<template>
  <v-col class="pa-0">
    <v-card class="fill-height my-card">
      <v-row class="justify-center ma-lg-16">
        <h1>Turbulence Analysis</h1>
      </v-row>
      <v-row class="justify-center ma-lg-16">
        <v-autocomplete
            v-model="selectedSourceAirport"
            v-model:search="sourceAirportBeingEntered"
            :loading="loadingSourceAirportCompletions"
            :items="sourceAirportCompletions"
            item-title="icao"
            item-value="icao"
            item-props="['icao', 'name']"
            :custom-filter="filterCompletions"
            class="mx-4"
            density="comfortable"
            hide-no-data
            hide-details
            label="Source Airport:"
            style="max-width: 300px;"
        >
          <template v-slot:item="{ props, item }">
            <v-list-item
                v-bind="props"
                :title="item?.raw?.city + ' - ' + item?.raw?.name"
                :subtitle="item?.raw?.icao + ', ' + item?.raw?.iata"
            ></v-list-item>
          </template>        </v-autocomplete>
      </v-row>
      <v-row class="justify-center">
        <v-col>
          <h2 class="text-center">Flights:</h2>
          <ul class="text-center">
            <li v-for="flight in flights" :key="flight.flightNumber">
              {{ flight.flightNumber }} - {{ flight.departureDateTime }}
            </li>
          </ul>
        </v-col>
      </v-row>
    </v-card>
  </v-col>
</template>

<script setup lang="ts">
import {computed, ref, watch} from 'vue'
import {useFlightsStore} from '@/stores/flights-store'
import type {Airport} from "@/objects/airports/airports";
import StringsUtils from "@/util/strings";


const flightsStore = useFlightsStore()
const selectedSourceAirport = ref("")
const sourceAirportBeingEntered = ref("")
const sourceAirportCompletions = ref([] as Array<Airport>)
const loadingSourceAirportCompletions = ref(false)
const selectedDestAirport = ref("")
const destAirportBeingEntered = ref("")
const destAirportCompletions = ref([] as Array<Airport>)
const loadingDestAirportCompletions = ref(false)
const selectedRoute = computed(() => {
  return {
    sourceAirport: selectedSourceAirport.value,
    destinationAirport: selectedDestAirport.value
  }
})

watch(sourceAirportBeingEntered, async (partialSourceAirport) => {
  loadingSourceAirportCompletions.value = true
  await flightsStore.fetchAirports(partialSourceAirport)
  sourceAirportCompletions.value = flightsStore.getAirports(partialSourceAirport)
  loadingSourceAirportCompletions.value = false
})
watch(destAirportBeingEntered, async (partialDestAirport) => {
  loadingDestAirportCompletions.value = true
  await flightsStore.fetchAirports(partialDestAirport)
  destAirportCompletions.value = flightsStore.getAirports(partialDestAirport)
  loadingDestAirportCompletions.value = false
})

const flights = computed(() => {
  return flightsStore.getFlightsByRoute(selectedRoute.value)
})
// TODO(gm2211): Instead of returning bool here, return a list of [start, end] tuples for each matched chunk so that we
//               can get highlighting - this will require modifying the editDistance function to return a list of
//               [start, end] tuples
const filterCompletions = (value: string, query: string, item?: any) => {
  const airport: Airport | undefined = item?.raw as (Airport | undefined)
  if (airport) {
    return StringsUtils.similar(query, airport.icao)
        || StringsUtils.similar(query, airport.iata)
        || StringsUtils.similar(query, airport.name)
        || StringsUtils.similar(query, airport.city)
        || StringsUtils.similar(query, airport.country)
  }
  return StringsUtils.editDistance(query, value) < 3
}
</script>
