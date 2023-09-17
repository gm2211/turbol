<template>
  <v-col class="fill-height d-flex flex-column">
    <v-row class="flex-grow-0 pb-2">
      <v-date-picker/>
      <v-text-field class="ma-auto w-25"/>
    </v-row>
    <v-row class="flex-grow-1">
      <WorldMap :flight-path="flightPath" />
    </v-row>
  </v-col>
</template>

<script setup lang="ts">
import WorldMap from '@/components/map/WorldMap.vue'
import {ref, watch} from 'vue'
import { LatLngLiteral } from 'leaflet'
import { useRoute } from 'vue-router'
import FlightNumber from '@/objects/flights/shared'
import FlightSearchBox from '@/components/search/FlightSearchBox.vue'

const flightPath = ref<LatLngLiteral[]>([
  { lat: 38.7994, lng: -55.6731 } as LatLngLiteral,
  { lat: 40, lng: -56 } as LatLngLiteral,
  { lat: 41, lng: -59 } as LatLngLiteral,
  { lat: 41, lng: -90 } as LatLngLiteral,
  { lat: 29, lng: -80 } as LatLngLiteral
])
const route = useRoute()
const flightNumber = ref<typeof FlightNumber>(route.params.flightNumber as any)
const searchBox = ref<typeof FlightSearchBox>(undefined as any)

watch(searchBox.value?.selectedRoute, updatedRoute => {
  flightNumber.value = updatedRoute?.flightNumber
})
</script>
