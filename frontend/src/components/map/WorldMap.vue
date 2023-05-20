<template>
    <v-col class="fill-height">
        <v-card class="fill-height my-card w-100" id="map"></v-card>
    </v-col>
</template>

<script setup lang="ts">
import mapboxgl from "mapbox-gl";
import {onMounted} from "vue";
import {useConfigStore} from "@/stores/config-store";

let map = undefined
const configStore = useConfigStore()

onMounted(() => {
    configStore.fetchConfig()

    mapboxgl.accessToken = configStore.getMapboxToken();

    map = new mapboxgl.Map({
        container: 'map',
        style: 'mapbox://styles/mapbox/dark-v11',
        center: [-96, 37.8],
        zoom: 3,
        maxZoom: 10,
        pitch: 40
    });
})

defineExpose({map})
</script>
