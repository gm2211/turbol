<template>
  <WorldMap ref="map" />
</template>

<script setup lang="ts">
import WorldMap from '@/components/map/WorldMap.vue'
import { onMounted, ref } from 'vue'
import mapboxgl from 'mapbox-gl'

const map = ref(undefined as mapboxgl.Map)

onMounted(() => {
  // San Francisco
  const origin = [-122.414, 37.776]

  // Washington DC
  const destination = [-77.032, 38.913]

  // A simple line from origin to destination.
  const route = {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [origin, destination]
        }
      }
    ]
  }

  // A single point that animates along the route.
  // Coordinates are initially set to origin.
  const point = {
    type: 'FeatureCollection',
    features: [
      {
        type: 'Feature',
        properties: {},
        geometry: {
          type: 'Point',
          coordinates: origin
        }
      }
    ]
  }

  map.value.on('load', () => {
    // Add a source and layer displaying a point which will be animated in a circle.
    map.value.addSource('route', {
      type: 'geojson',
      data: route
    })

    map.value.addSource('point', {
      type: 'geojson',
      data: point
    })

    map.value.addLayer({
      id: 'route',
      source: 'route',
      type: 'line',
      paint: {
        'line-width': 2,
        'line-color': '#007cbf'
      }
    })

    map.value.addLayer({
      id: 'point',
      source: 'point',
      type: 'symbol',
      layout: {
        'icon-image': 'airport',
        'icon-size': 1.5,
        'icon-rotate': ['get', 'bearing'],
        'icon-rotation-alignment': 'map',
        'icon-allow-overlap': true,
        'icon-ignore-placement': true
      }
    })
  })
})
</script>