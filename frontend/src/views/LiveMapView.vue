<template>
  <div class="map-page">
    <div ref="mapEl" class="map" />

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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
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
    for (const a of traffic.aircraft) {
      L.marker([a.lat, a.lon], { icon: planeIcon(a.category, a.trackDeg ?? 0, planeSize()) })
        .bindPopup(popupHtml(a), { className: 'plane-popup', closeButton: true, minWidth: 220 })
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
  mapEl.value!.addEventListener('click', onPopupClick)
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
