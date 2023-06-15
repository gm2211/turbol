<template>
  <v-col class="fill-height">
    <v-card class="fill-height my-card w-100">
      <l-map ref="map" v-model:zoom="zoom" v-model:center="center" :useGlobalLeaflet="false">
        <l-tile-layer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            layer-type="base"
            name="Stadia Maps Basemap"></l-tile-layer>
      </l-map>
    </v-card>
  </v-col>
</template>

<script setup lang="ts">
import "leaflet/dist/leaflet.css"
import {LMap, LTileLayer} from "@vue-leaflet/vue-leaflet"
import {ref} from "vue";

const zoom = ref(3)
const center = ref([38.7994, -55.6731])
</script>
