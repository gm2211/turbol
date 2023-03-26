<template>
  <v-col class="pa-0">
    <v-card class="fill-height my-card">
      <v-row class="justify-center ma-lg-16">
        <h1>Turbulence Analysis</h1>
      </v-row>
      <v-row class="justify-center ma-lg-16">
        <v-autocomplete
            v-model="selectedFlight"
            v-model:search="selectedFlight"
            :loading="loading"
            :items="items"
            class="mx-4"
            density="comfortable"
            hide-no-data
            hide-details
            label="Source Airport:"
            style="max-width: 300px;"
        ></v-autocomplete>
      </v-row>
      <v-row class="justify-center">
        <v-col>
          <h2 class="text-center">Flight path:</h2>
          <ul class="text-center">
            <li v-for="waypoint in flight?.waypoints" :key="waypoint.toString()">
              {{ waypoint.lat }} - {{ waypoint.lon }}
            </li>
          </ul>
        </v-col>
      </v-row>
    </v-card>
  </v-col>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue'
import {useFlightsStore} from '@/stores/flights-store'


const flightsStore = useFlightsStore()
const selectedFlight = ref("")
const flightNumberBeingEntered = ref("")
const clearFlightSelection = () => {
  selectedFlight.value = ""
  flightNumberBeingEntered.value = ""
}
const selectFlightAndLoad = () => {
  selectedFlight.value = flightNumberBeingEntered.value
  flightNumberBeingEntered.value = ""
  flightsStore.loadIfNotPresent(selectedFlight.value)
}
const flight = computed(() => {
  if (!selectedFlight.value) {
    return;
  }
  return flightsStore.getFlightPlan(selectedFlight.value)
})
</script>
