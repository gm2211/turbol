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

    <EdrHover :map="leafletMap" :frame-id="selectedFrame?.id" :level-ft="levelFt" :level-label="levelLabel" />

    <div class="bottom-left">
      <TurbulenceLegend :title="`Turbulence at ${levelLabel}`" />
    </div>
  </div>
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { type Component, computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import TurbulenceLegend from '@/components/turbulence/TurbulenceLegend.vue'
import EdrHover from '@/components/turbulence/EdrHover.vue'
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

const router = useRouter()
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
const leafletMap = shallowRef<L.Map>()
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
  const color = categoryColors[a.category]
  const edr = a.edr != null ? `<span class="pp-edr">EDR ${a.edr.toFixed(2)}</span>` : ''
  const q = a.callsign ? encodeURIComponent(a.callsign) : undefined
  const action = (href: string, icon: string, label: string) =>
    `<a class="pp-action" href="${href}" data-route="${href}">` +
    `<i class="mdi ${icon}"></i><span>${label}</span></a>`
  return (
    `<div class="pp">` +
    `<div class="pp-head"><span class="pp-callsign">${a.callsign ?? a.hex}</span>` +
    (a.typeCode ? `<span class="pp-type">${a.typeCode}</span>` : '') +
    `</div>` +
    `<div class="pp-stats">${flightLevel(a.altitudeFt)} · ${Math.round(a.groundSpeedKts ?? 0)} kt</div>` +
    `<div class="pp-turb"><span class="pp-badge" style="--c:${color}"><span class="pp-dot"></span>` +
    `${categoryLabels[a.category]}</span>${edr}</div>` +
    `<div class="pp-actions">` +
    action(`/follow/${a.hex}`, 'mdi-crosshairs-gps', 'Follow') +
    (q ? action(`/analyze?q=${q}`, 'mdi-airplane-search', 'Analyze') + action(`/3d?q=${q}`, 'mdi-cube-outline', '3D') : '') +
    `</div></div>`
  )
}

/** Popup buttons are plain HTML, so route their clicks through the SPA router instead of reloading the page. */
function onPopupClick(e: MouseEvent) {
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  const link = (e.target as HTMLElement).closest<HTMLAnchorElement>('a[data-route]')
  if (!link) return
  e.preventDefault()
  router.push(link.dataset.route!)
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
        .bindPopup(popupHtml(a), { className: 'plane-popup', closeButton: true, minWidth: 220 })
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
  leafletMap.value = map
  mapEl.value!.addEventListener('click', onPopupClick)
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
  mapEl.value?.removeEventListener('click', onPopupClick)
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
/* The 2D map's zoom buttons and EDR readout belong to the hidden map in 3D (3D has its own tooltip). */
.is-3d :deep(.leaflet-control-container),
.is-3d :deep(.edr-tip) {
  display: none;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
/* Aircraft popup: dark glass card matching the side panels, with icon buttons for each action. */
:deep(.plane-popup .leaflet-popup-content-wrapper) {
  background: rgba(20, 34, 48, 0.94);
  color: #fff;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(6px);
}
:deep(.plane-popup .leaflet-popup-tip) {
  background: rgba(20, 34, 48, 0.94);
}
:deep(.plane-popup .leaflet-popup-content) {
  margin: 12px 14px;
  font-size: 13px;
  line-height: 1.4;
}
:deep(.plane-popup a.leaflet-popup-close-button) {
  color: rgba(255, 255, 255, 0.6);
  top: 4px;
  right: 4px;
}
:deep(.plane-popup a.leaflet-popup-close-button:hover) {
  color: #fff;
}
:deep(.pp-head) {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-right: 18px;
}
:deep(.pp-callsign) {
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0.02em;
}
:deep(.pp-type) {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.12);
  color: rgba(255, 255, 255, 0.85);
}
:deep(.pp-stats) {
  color: rgba(255, 255, 255, 0.7);
  margin-top: 2px;
}
:deep(.pp-turb) {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}
:deep(.pp-badge) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  border-radius: 999px;
  font-weight: 600;
  color: #fff;
  background: color-mix(in srgb, var(--c) 30%, transparent);
  border: 1px solid color-mix(in srgb, var(--c) 70%, transparent);
}
:deep(.pp-dot) {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c);
}
:deep(.pp-edr) {
  color: rgba(255, 255, 255, 0.6);
  font-size: 12px;
}
:deep(.pp-actions) {
  display: flex;
  gap: 6px;
  margin-top: 12px;
}
:deep(a.pp-action) {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-height: 48px;
  padding: 6px 8px;
  border-radius: 8px;
  color: #fff;
  text-decoration: none;
  font-size: 12px;
  font-weight: 600;
  background: rgba(47, 155, 228, 0.18);
  border: 1px solid rgba(47, 155, 228, 0.45);
  transition: background 0.15s, transform 0.1s;
}
:deep(a.pp-action .mdi) {
  font-size: 20px;
  line-height: 1;
  color: #7cc4f2;
}
:deep(a.pp-action:hover),
:deep(a.pp-action:focus-visible) {
  background: #2f9be4;
  outline: none;
}
:deep(a.pp-action:hover .mdi),
:deep(a.pp-action:focus-visible .mdi) {
  color: #fff;
}
:deep(a.pp-action:active) {
  transform: scale(0.96);
}
</style>
