<template>
    <v-card class="fill-height my-card d-flex flex-column">
        <v-row class="flex-grow-0 mt-8 ml-7 mr-7">
            <FlightSearchBox ref="searchBox"/>
        </v-row>
        <v-row class="flex-grow-1 mb-10">
            <v-card class="my-card pa-10 mt-2 mb-10 ml-10 mr-10 fill-height">
                <ul class="fill-height justify-center text-center">
                    <li v-for="flight in flights" :key="flight.flightNumber">
                        {{ flight.flightNumber }} - {{ flight.departureDateTime }}
                    </li>
                </ul>
            </v-card>
        </v-row>
    </v-card>
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
