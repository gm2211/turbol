<template>
  <div class="map-page">
    <div ref="mapEl" class="map" />

    <div class="panel top-left">
      <div class="panel-title">Live turbulence</div>
      <div class="muted">
        {{ aircraftCount.toLocaleString() }} aircraft in view
        <span v-if="!trafficComplete"> (loading more)</span>
      </div>
      <div class="muted" v-if="status?.nowcast">
        GTG-N nowcast valid {{ formatUtc(status.nowcast.validTime) }}
      </div>
      <div class="muted" v-else>Turbulence data loading…</div>
    </div>

    <div class="panel top-right controls">
      <v-select
        v-model="levelFt"
        :items="levelItems"
        label="Map altitude"
        density="compact"
        hide-details
        variant="outlined"
        class="mb-3"
      />
      <div class="text-caption mb-1">
        {{ selectedFrameLabel }}
      </div>
      <v-slider
        v-model="frameIndex"
        :min="0"
        :max="Math.max(frames.length - 1, 0)"
        :step="1"
        :disabled="frames.length < 2"
        hide-details
        density="compact"
        color="primary"
      />
    </div>

    <div class="bottom-left">
      <TurbulenceLegend :title="`Turbulence at ${levelLabel}`" />
    </div>
  </div>
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import TurbulenceLegend from '@/components/turbulence/TurbulenceLegend.vue'
import { planeIcon } from '@/components/turbulence/planeIcon'
import {
  type AircraftView,
  type FrameInfo,
  type TurbulenceStatus,
  categoryColors,
  categoryLabels,
  fetchStatus,
  fetchTraffic,
  flightLevel,
  formatUtc,
  tileUrl
} from '@/api/turbulence'
import { baseMapAttribution, baseMapUrl } from '@/components/turbulence/baseMap'

const mapEl = ref<HTMLDivElement>()
const status = ref<TurbulenceStatus>()
const levelFt = ref(35000)
const frameIndex = ref(0)
const aircraftCount = ref(0)
const trafficComplete = ref(true)

const levelItems = [10000, 15000, 20000, 25000, 30000, 33000, 35000, 37000, 39000, 41000].map((ft) => ({
  title: ft >= 18000 ? `FL${ft / 100}` : `${ft.toLocaleString()} ft`,
  value: ft
}))
const levelLabel = computed(() => levelItems.find((i) => i.value === levelFt.value)?.title ?? '')

/** Nowcast first, then forecast hours that are still in the future. */
const frames = computed<FrameInfo[]>(() => {
  const s = status.value
  if (!s) return []
  const now = Date.now()
  const future = s.forecast.filter((f) => new Date(f.validTime).getTime() > now + 15 * 60 * 1000)
  return [...(s.nowcast ? [s.nowcast] : []), ...future]
})
const selectedFrame = computed(() => frames.value[frameIndex.value])
const selectedFrameLabel = computed(() => {
  const f = selectedFrame.value
  if (!f) return 'No turbulence data yet'
  if (f.kind === 'nowcast') return `Now: GTG-N nowcast, ${formatUtc(f.validTime)}`
  const hours = Math.round((new Date(f.validTime).getTime() - Date.now()) / 3600000)
  return `Forecast +${hours}h: GTG, valid ${formatUtc(f.validTime)}`
})

let map: L.Map | undefined
let turbulenceLayer: L.TileLayer | undefined
const planes = L.layerGroup()
let trafficTimer: number | undefined
let statusTimer: number | undefined

function updateTurbulenceLayer() {
  if (!map) return
  const frame = selectedFrame.value
  if (turbulenceLayer) {
    map.removeLayer(turbulenceLayer)
    turbulenceLayer = undefined
  }
  if (!frame) return
  turbulenceLayer = L.tileLayer(tileUrl(frame.id, levelFt.value), {
    opacity: 0.85,
    maxNativeZoom: 9,
    zIndex: 5,
    attribution: 'Turbulence: NOAA GTG-N / GTG'
  }).addTo(map)
}

function popupHtml(a: AircraftView): string {
  const edr = a.edr != null ? ` (EDR ${a.edr.toFixed(2)})` : ''
  return (
    `<div style="min-width:170px"><b>${a.callsign ?? a.hex}</b> ${a.typeCode ?? ''}<br/>` +
    `${flightLevel(a.altitudeFt)} · ${Math.round(a.groundSpeedKts ?? 0)} kt<br/>` +
    `Turbulence now: <b style="color:${categoryColors[a.category]}">${categoryLabels[a.category]}</b>${edr}` +
    `</div>`
  )
}

const planeSize = () => ((map?.getZoom() ?? 5) <= 5 ? 14 : (map?.getZoom() ?? 5) <= 7 ? 18 : 22)

async function refreshTraffic() {
  if (!map) return
  const b = map.getBounds()
  const bounds = {
    south: Math.max(b.getSouth(), -85),
    west: Math.max(b.getWest(), -180),
    north: Math.min(b.getNorth(), 85),
    east: Math.min(b.getEast(), 180)
  }
  try {
    const traffic = await fetchTraffic(bounds)
    planes.clearLayers()
    for (const a of traffic.aircraft) {
      L.marker([a.lat, a.lon], { icon: planeIcon(a.category, a.trackDeg ?? 0, planeSize()) })
        .bindPopup(popupHtml(a))
        .addTo(planes)
    }
    aircraftCount.value = traffic.aircraft.length
    trafficComplete.value = traffic.complete
  } catch (e) {
    console.warn('traffic refresh failed', e)
  }
}

async function refreshStatus() {
  try {
    status.value = await fetchStatus()
  } catch (e) {
    console.warn('status refresh failed', e)
  }
}

watch([selectedFrame, levelFt], updateTurbulenceLayer)

onMounted(async () => {
  map = L.map(mapEl.value!, { zoomControl: true, worldCopyJump: true }).setView([39.5, -97], 5)
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  planes.addTo(map)
  map.on('moveend', refreshTraffic)
  await refreshStatus()
  updateTurbulenceLayer()
  await refreshTraffic()
  trafficTimer = window.setInterval(refreshTraffic, 15000)
  statusTimer = window.setInterval(refreshStatus, 120000)
})

onBeforeUnmount(() => {
  window.clearInterval(trafficTimer)
  window.clearInterval(statusTimer)
  map?.remove()
})
</script>

<style scoped>
.map-page {
  position: relative;
  width: 100%;
  height: calc(100vh - 80px);
  border-radius: 8px;
  overflow: hidden;
}
.map {
  position: absolute;
  inset: 0;
}
.panel {
  position: absolute;
  z-index: 1000;
  background: rgba(255, 255, 255, 0.94);
  border-radius: 8px;
  padding: 10px 12px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
  font-size: 13px;
}
.panel-title {
  font-weight: 700;
  font-size: 15px;
}
.muted {
  color: #475569;
}
.top-left {
  top: 12px;
  left: 56px;
}
.top-right {
  top: 12px;
  right: 12px;
  width: 250px;
}
.bottom-left {
  position: absolute;
  z-index: 1000;
  bottom: 24px;
  left: 12px;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
</style>
