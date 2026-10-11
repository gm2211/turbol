<template>
  <div class="map-page" :class="{ 'is-3d': mode === '3d' }">
    <div ref="mapEl" class="map" />

    <component
      :is="Map3D"
      v-if="mode === '3d' && Map3D"
      ref="map3d"
      :view="view3d"
      :aircraft="aircraft"
      :frame="selectedFrame"
      :status="status"
      :level-ft="levelFt"
      @move="on3dMove"
      @select="on3dSelect"
    />

    <v-btn-toggle
      :model-value="mode"
      mandatory
      density="compact"
      theme="dark"
      color="var(--my-accent)"
      class="mode-toggle glass-panel"
      @update:model-value="setMode"
    >
      <v-btn value="2d" size="small" :loading="loading3d && mode === '2d'">2D</v-btn>
      <v-btn value="3d" size="small" title="Or shift+drag the map">3D</v-btn>
    </v-btn-toggle>

    <div class="panel glass-panel top-left">
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

    <div class="panel glass-panel top-right controls">
      <v-select
        v-model="levelFt"
        :items="levelItems"
        label="Map altitude"
        density="compact"
        hide-details
        variant="outlined"
        theme="dark"
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
        theme="dark"
        color="var(--my-accent)"
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
import { type Component, computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
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
import type { MapView } from '@/components/turbulence/Map3D.vue'

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
const aircraft = shallowRef<AircraftView[]>([])
const markers = new Map<string, L.Marker>()
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
    `<br/><a href="/follow/${a.hex}">Follow live</a>` +
    (a.callsign
      ? ` · <a href="/analyze?q=${encodeURIComponent(a.callsign)}">Analyze flight</a>` +
        ` · <a href="/3d?q=${encodeURIComponent(a.callsign)}">3D</a>`
      : '') +
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
    markers.clear()
    for (const a of traffic.aircraft) {
      const marker = L.marker([a.lat, a.lon], { icon: planeIcon(a.category, a.trackDeg ?? 0, planeSize()) })
        .bindPopup(popupHtml(a))
        .addTo(planes)
      markers.set(a.hex, marker)
    }
    aircraft.value = traffic.aircraft
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

// 3D mode: deck.gl drawn over the Leaflet map, loaded only the first time it's opened so 2D stays light.
interface Map3DHandle {
  setRotation(pitch: number, bearing: number): void
  tiltUp(): Promise<MapView>
  flatten(): Promise<MapView>
}
const mode = ref<'2d' | '3d'>('2d')
const Map3D = shallowRef<Component>()
const map3d = ref<Map3DHandle>()
const loading3d = ref(false)
const view3d = ref<MapView>({ lat: 39.5, lon: -97, zoom: 5, pitch: 0, bearing: 0 })

async function enter3d(pitch = 0, bearing = 0) {
  if (!map || mode.value === '3d') return
  const c = map.getCenter()
  view3d.value = { lat: c.lat, lon: c.lng, zoom: map.getZoom(), pitch, bearing }
  if (!Map3D.value) {
    loading3d.value = true
    try {
      Map3D.value = (await import('@/components/turbulence/Map3D.vue')).default
    } finally {
      loading3d.value = false
    }
  }
  mode.value = '3d'
}

async function exit3d(): Promise<void> {
  if (mode.value !== '3d') return
  const v = (await map3d.value?.flatten()) ?? view3d.value
  map?.setView([v.lat, v.lon], Math.round(v.zoom), { animate: false })
  mode.value = '2d'
}

async function setMode(m: '2d' | '3d') {
  if (m === '2d') return exit3d()
  await enter3d()
  // Wait for the 3D component to mount, then tilt up.
  await nextTick()
  map3d.value?.tiltUp()
}

/** Keeps the hidden 2D map on the 3D camera so traffic loads for the area in view and 2D resumes there. */
function on3dMove(v: MapView) {
  view3d.value = v
  // A tilted camera sees further than straight down: fetch traffic for a wider area.
  map?.setView([v.lat, v.lon], Math.max(Math.round(v.zoom - v.pitch / 40), 2), { animate: false })
}

async function on3dSelect(hex: string) {
  await exit3d()
  const marker = markers.get(hex)
  if (marker && map) {
    map.panTo(marker.getLatLng(), { animate: false })
    marker.openPopup()
  }
}

// Shift+drag on the 2D map opens 3D and keeps tilting and rotating for the rest of that drag.
function onShiftDrag(e: PointerEvent) {
  if (mode.value !== '2d' || !e.shiftKey || e.button !== 0 || !mapEl.value) return
  const rect = mapEl.value.getBoundingClientRect()
  const start = { x: e.clientX, y: e.clientY }
  const startY = Math.max(e.clientY - rect.top, 100)
  let started = false
  const move = (ev: PointerEvent) => {
    const dx = ev.clientX - start.x
    const dy = ev.clientY - start.y
    if (!started && Math.hypot(dx, dy) < 4) return
    // Drag up to tilt (all the way up reaches the steepest tilt), sideways to rotate, like deck.gl's own rotate.
    const pitch = Math.min(Math.max((-dy / startY) * 85, 0), 85)
    const bearing = (180 * dx) / rect.width
    if (!started) {
      started = true
      enter3d(pitch, bearing)
    } else if (map3d.value) map3d.value.setRotation(pitch, bearing)
    else view3d.value = { ...view3d.value, pitch, bearing }
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

onMounted(async () => {
  // boxZoom off: shift+drag opens 3D instead.
  map = L.map(mapEl.value!, { zoomControl: true, worldCopyJump: true, boxZoom: false }).setView([39.5, -97], 5)
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  planes.addTo(map)
  map.on('moveend', refreshTraffic)
  mapEl.value!.addEventListener('pointerdown', onShiftDrag)
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
  padding: 10px 12px;
  font-size: 13px;
}
.panel-title {
  font-weight: 700;
  font-size: 15px;
}
.muted {
  color: rgba(255, 255, 255, 0.75);
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
.mode-toggle {
  position: absolute;
  z-index: 1000;
  right: 12px;
  bottom: 24px;
}
/* The 2D map's zoom buttons would steer the hidden map in 3D. */
.is-3d :deep(.leaflet-control-container) {
  display: none;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
</style>
