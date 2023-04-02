<template>
    <v-col class="pa-0">
        <v-card class="fill-height my-card">
            <v-row class="justify-center ma-lg-16">
                <h1>Turbulence Analysis</h1>
            </v-row>
            <v-row class="justify-center">
                <AirportAutocomplete ref="departure" :css-class="autoCompleteCss" label="Departure Airport"/>
                <AirportAutocomplete ref="arrival" :css-class="autoCompleteCss" label="Arrival Airport"/>
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
import {computed, ref} from "vue";
import AirportAutocomplete from "@/components/search/AirportAutocomplete.vue";
import {useFlightsStore} from "@/stores/flights-store";
import type {Airport} from "@/objects/airports/airports";

const autoCompleteCss = "justify-center v-col-2 font-weight-bold"
const flightsStore = useFlightsStore()
const departure = ref(undefined as any)
const destination = ref(undefined as any)
const selectedRoute = computed(() => {
    return {
        sourceAirport: departure.value?.selectedAirport.value || {} as Airport,
        destinationAirport: destination.value?.selectedAirport.value || {} as Airport
    }
})
const flights = computed(() => {
    return flightsStore.getFlightsByRoute(selectedRoute.value)
})
</script>
