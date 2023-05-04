<template>
  <v-card class="my-card pa-10 mt-10 mr-10 ml-10 mb-2">
    <v-row>
      <v-col>
        <v-row class="justify-end">
          <AirportAutocomplete
            ref="departureAirport"
            :css-class="autoCompleteCss"
            label="Departure Airport"
          />
        </v-row>
      </v-col>
      <v-col>
        <v-row class="justify-start">
          <AirportAutocomplete
            ref="arrivalAirport"
            :css-class="autoCompleteCss"
            label="Arrival Airport"
          />
        </v-row>
      </v-col>
    </v-row>
  </v-card>
</template>
<script setup lang="ts">
import AirportAutocomplete from '@/components/search/AirportAutocomplete.vue'
import { computed, ref } from 'vue'
import type { Airport } from '@/objects/airports/airports'

const autoCompleteCss = 'justify-center v-col-2 font-weight-bold'
const departureAirport = ref(undefined as any)
const arrivalAirport = ref(undefined as any)
const selectedRoute = computed(() => {
  return {
    departureAirport: departureAirport.value?.selectedAirport.value || ({} as Airport),
    arrivalAirport: arrivalAirport.value?.selectedAirport.value || ({} as Airport)
  }
})
defineExpose({ selectedRoute })
</script>
