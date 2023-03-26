<template>
  <v-col class="pa-0">
    <v-card class="fill-height my-card">
      <v-row class="justify-center ma-lg-16">
        <h1>Turbulence Analysis</h1>
      </v-row>
      <v-row class="justify-center ma-lg-16">
        <v-col>
          <v-row>
            <v-text-field
                v-model="selectedFlight"
                label="Enter flight number"
                outlined
                @keyup.enter="getFlightPath"
            ></v-text-field>
          </v-row>
        </v-col>
      </v-row>
      <v-row class="justify-center">
        <v-col>
          <h2 class="text-center">Flight path:</h2>
          <ul>
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
const selectedFlight = ref()
const getFlightPath = () => {
  flightsStore.getFlightPlan(selectedFlight.value)
}
const flight = computed(() => {
  if (!selectedFlight.value) {
    return null
  }
  return flightsStore.getFlightPlan(selectedFlight.value)
})
</script>
