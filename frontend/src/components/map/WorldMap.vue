<template>
    <div class="map-container">
        <div id="map"></div>
    </div>
</template>

<script setup lang="ts">
import mapboxgl from "mapbox-gl";
import {onMounted} from "vue";

onMounted(() => {
// Replace 'your_mapbox_access_token' with your actual Mapbox access token
    mapboxgl.accessToken = "sk.eyJ1IjoiZ21lY29jY2kiLCJhIjoiY2xoYTJ0ajlhMGRjMDNzcGh2cjNldmo5YyJ9.XfHe6yRHDm-AM8ePgBKKBA";

    const map = new mapboxgl.Map({
        container: 'map',
        style: 'mapbox://styles/mapbox/dark-v11',
        center: [-96, 37.8],
        zoom: 3,
        pitch: 40
    });
})
</script>
