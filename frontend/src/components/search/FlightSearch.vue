<template>
    <v-col class="fill-height d-flex flex-column" style="width: 100%; height: 100%">
        <v-row class="flex-grow-0 mt-1 mb-1">
            <FlightSearchBox ref="searchBox"/>
        </v-row>
        <v-row class="flex-grow-1 mt-1 mb-1">
            <v-card class="my-card">
                <ul class="fill-height justify-center text-center">
                    <li v-for="flight in flights" :key="flight.flightNumber">
                        {{ flight.flightNumber }} - {{ flight.departureDateTime }}
                    </li>
                </ul>
            </v-card>
        </v-row>
    </v-col>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {useFlightsStore} from '@/stores/flights-store'
import FlightSearchBox from '@/components/search/FlightSearchBox.vue'

const searchBox = ref(undefined as any)
const flightsStore = useFlightsStore()
const flights = computed(() => {
    return flightsStore.getFlightsByRoute(searchBox.value?.selectedRoute.value)
})
</script>
