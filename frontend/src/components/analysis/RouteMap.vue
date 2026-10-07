<template>
  <div ref="mapEl" class="route-map" />
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Place, RoutePoint } from '@/api/flights'
import type { AircraftView } from '@/api/turbulence'
import { categoryColors, flightLevel } from '@/api/turbulence'
import { planeIcon } from '@/components/turbulence/planeIcon'
import { baseMapAttribution, baseMapUrl } from '@/components/turbulence/baseMap'

const props = defineProps<{
  points: RoutePoint[]
  origin?: Place
  destination?: Place
  aircraft?: AircraftView
  trail?: [number, number][]
  worst?: [number, number]
  follow?: boolean
}>()

const mapEl = ref<HTMLDivElement>()
let map: L.Map | undefined
const layers = L.layerGroup()
let fitted = false

function draw() {
  if (!map) return
  layers.clearLayers()
  // One polyline per run of same-category points, so the route reads like a turbulence ribbon.
  const pts = props.points
  let start = 0
  for (let i = 1; i <= pts.length; i++) {
    if (i === pts.length || pts[i].category !== pts[start].category) {
      const run = pts.slice(start, Math.min(i + 1, pts.length)).map((p) => [p.lat, p.lon] as [number, number])
      L.polyline(run, { color: categoryColors[pts[start].category], weight: 6, opacity: 0.9 }).addTo(layers)
      start = i
    }
  }
  if (props.trail && props.trail.length > 1) {
    L.polyline(props.trail, { color: '#334155', weight: 3, dashArray: '4 6', opacity: 0.8 }).addTo(layers)
  }
  for (const p of [props.origin, props.destination]) {
    if (p) {
      L.circleMarker([p.lat, p.lon], { radius: 6, color: '#0f172a', fillColor: '#fff', fillOpacity: 1, weight: 2 })
        .bindTooltip(p.code, { permanent: true, direction: 'top', offset: [0, -6] })
        .addTo(layers)
    }
  }
  if (props.worst) {
    L.circleMarker(props.worst, { radius: 9, color: '#dc2626', weight: 3, fillOpacity: 0 })
      .bindTooltip('Worst bump', { direction: 'right' })
      .addTo(layers)
  }
  const a = props.aircraft
  if (a) {
    L.marker([a.lat, a.lon], { icon: planeIcon(a.category, a.trackDeg ?? 0, 30, true), zIndexOffset: 1000 })
      .bindTooltip(`${a.callsign ?? a.hex} · ${flightLevel(a.altitudeFt)}`, { direction: 'right', offset: [14, 0] })
      .addTo(layers)
  }
  if (props.follow && a) {
    map.panTo([a.lat, a.lon], { animate: true })
  } else if (!fitted && pts.length > 1) {
    map.fitBounds(L.latLngBounds(pts.map((p) => [p.lat, p.lon] as [number, number])), { padding: [30, 30] })
    fitted = true
  }
}

watch(
  () => [props.points, props.aircraft, props.trail],
  () => {
    if (!props.follow) fitted = false
    draw()
  }
)

onMounted(() => {
  map = L.map(mapEl.value!, { worldCopyJump: true }).setView([39.5, -97], props.follow ? 7 : 4)
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  layers.addTo(map)
  draw()
})
onBeforeUnmount(() => map?.remove())
</script>

<style scoped>
.route-map {
  width: 100%;
  height: 100%;
  min-height: 320px;
  border-radius: 8px;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
</style>
