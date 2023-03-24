<template>
  <div>
    <h2>Flight ID: {{ flight123?.flightNumber }}</h2>
    <h2>Flight path:</h2>
    <ul>
      <li v-for="waypoint in flight123?.waypoints" :key="waypoint.toString()">
        {{ waypoint.lat }} - {{ waypoint.lon }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useFlightsStore } from '../stores/flights-store'

const flightsStore = useFlightsStore()
const flight123 = computed(() => {
  return flightsStore.getFlightPlan('123')
})
onMounted(() => {
  flightsStore.loadIfNotPresent('123')
})
</script>
