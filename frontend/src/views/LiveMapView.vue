<template>
  <div class="map-page">
    <div ref="mapEl" class="map" />

    <div class="panel glass-panel top-left">
      <div class="panel-title">Live turbulence</div>
      <div class="muted" v-if="status?.nowcast">
        GTG-N nowcast valid {{ formatUtc(status.nowcast.validTime) }}
      </div>
      <div class="muted" v-else-if="statusLoaded">Turbulence data loading…</div>

      <v-switch
        v-model="showAll"
        label="Show all flights"
        density="compact"
        hide-details
        theme="dark"
        color="var(--my-accent)"
        class="mt-1"
      />
      <div class="muted" v-if="showAll">
        {{ aircraftCount.toLocaleString() }} aircraft in view
        <span v-if="!trafficComplete"> (loading more)</span>
      </div>

      <form class="add-flight mt-2" @submit.prevent="addFlight">
        <v-text-field
          v-model="flightQuery"
          placeholder="Add flight, e.g. UA1517"
          density="compact"
          hide-details
          variant="outlined"
          theme="dark"
          :loading="adding"
          append-inner-icon="mdi-airplane-search"
          @click:append-inner="addFlight"
        />
      </form>
      <div class="error-text" v-if="addError">{{ addError }}</div>
      <div class="tracked" v-if="tracked.length">
        <v-chip
          v-for="t in tracked"
          :key="t.hex"
          size="small"
          closable
          theme="dark"
          :color="t.aircraft ? categoryColors[t.aircraft.category] : undefined"
          variant="flat"
          @click="panTo(t)"
          @click:close="removeFlight(t.hex)"
        >
          {{ t.label }}<span v-if="!t.aircraft">&nbsp;(landed?)</span>
        </v-chip>
      </div>
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
      <div class="d-flex align-center">
        <v-btn
          :icon="playing ? 'mdi-pause' : 'mdi-play'"
          size="small"
          variant="text"
          theme="dark"
          :disabled="frames.length < 2"
          :aria-label="playing ? 'Pause' : 'Play forecast'"
          @click="togglePlay"
        />
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
import { useRoute, useRouter } from 'vue-router'
import TurbulenceLegend from '@/components/turbulence/TurbulenceLegend.vue'
import { canvasPlane, planeIcon } from '@/components/turbulence/planeIcon'
import {
  type AircraftView,
  type FrameInfo,
  type TurbulenceStatus,
  categoryColors,
  categoryLabels,
  fetchAircraft,
  fetchStatus,
  fetchTraffic,
  flightLevel,
  formatUtc,
  tileUrl
} from '@/api/turbulence'
import { lookupFlight } from '@/api/flights'
import { baseMapAttribution, baseMapUrl } from '@/components/turbulence/baseMap'

interface TrackedFlight {
  /** What the user typed, which is also what goes in the URL (?flights=UA1517,DL5). */
  query: string
  label: string
  hex: string
  aircraft?: AircraftView
}

const route = useRoute()
const router = useRouter()
const mapEl = ref<HTMLDivElement>()
const status = ref<TurbulenceStatus>()
const statusLoaded = ref(false)
const levelFt = ref(35000)
const frameIndex = ref(0)
const aircraftCount = ref(0)
const trafficComplete = ref(true)
const showAll = ref(readShowAll())
const flightQuery = ref('')
const adding = ref(false)
const addError = ref('')
const tracked = ref<TrackedFlight[]>([])

function readShowAll(): boolean {
  try {
    return localStorage.getItem('turbol.showAllFlights') === 'true'
  } catch {
    return false
  }
}

const levelItems = [10000, 15000, 20000, 25000, 30000, 33000, 35000, 37000, 39000, 41000].map(
  (ft) => ({
    title: ft >= 18000 ? `FL${ft / 100}` : `${ft.toLocaleString()} ft`,
    value: ft
  })
)
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
  if (!f) return statusLoaded.value ? 'No turbulence data yet' : 'Now: GTG-N nowcast'
  if (f.kind === 'nowcast') return `Now: GTG-N nowcast, ${formatUtc(f.validTime)}`
  const hours = Math.round((new Date(f.validTime).getTime() - Date.now()) / 3600000)
  return `Forecast +${hours}h: GTG, valid ${formatUtc(f.validTime)}`
})

let map: L.Map | undefined
const planeRenderer = L.canvas({ padding: 0.2 })
const planes = L.layerGroup()
const trackedPlanes = L.layerGroup()
let trafficTimer: number | undefined
let trackedTimer: number | undefined
let statusTimer: number | undefined
let trafficRequest = 0

/**
 * The nowcast is requested as "latest" so tiles load in parallel with the status instead of after it. Its id only
 * goes in the URL once a newer nowcast replaces the one the page opened with, to bypass the browser cache.
 */
let openedWithNowcast: string | undefined
function frameTileUrl(frame: FrameInfo | undefined): string {
  if (!frame || frame.kind === 'nowcast') {
    const nowcast = status.value?.nowcast?.id
    const bust =
      nowcast && openedWithNowcast && nowcast !== openedWithNowcast ? `?v=${nowcast}` : ''
    return tileUrl('latest', levelFt.value) + bust
  }
  return tileUrl(frame.id, levelFt.value)
}

/**
 * One tile layer per frame at the selected altitude, all kept on the map: hidden ones are loaded one after another
 * once the visible one has finished (nearest hours first, so the first paint doesn't compete with them), and moving
 * the time slider or playing it only swaps which layer is visible.
 */
const turbulenceLayers = new Map<string, L.TileLayer>()
const overlayOpacity = 0.85
let preloadGeneration = 0

function addTurbulenceLayer(url: string, onLoaded?: () => void): L.TileLayer {
  const layer = L.tileLayer(url, {
    opacity: 0,
    maxNativeZoom: 9,
    zIndex: 5,
    attribution: 'Turbulence: NOAA GTG-N / GTG'
  })
  if (onLoaded) layer.once('load', onLoaded)
  turbulenceLayers.set(url, layer.addTo(map!))
  return layer
}

function updateTurbulenceLayers() {
  if (!map) return
  const selected = frameIndex.value
  const ordered = (frames.value.length ? frames.value : [undefined])
    .map((frame, i) => ({ url: frameTileUrl(frame), distance: Math.abs(i - selected) }))
    .sort((a, b) => a.distance - b.distance)
    .map((o) => o.url)
  const wanted = new Set(ordered)
  for (const [url, layer] of turbulenceLayers) {
    if (!wanted.has(url)) {
      map.removeLayer(layer)
      turbulenceLayers.delete(url)
    }
  }
  const generation = ++preloadGeneration
  const preloadNext = () => {
    if (generation !== preloadGeneration) return
    const next = ordered.find((url) => !turbulenceLayers.has(url))
    if (next) addTurbulenceLayer(next, preloadNext)
  }
  const visibleUrl = ordered[0]!
  const existing = turbulenceLayers.get(visibleUrl)
  const visible = existing ?? addTurbulenceLayer(visibleUrl, preloadNext)
  for (const layer of turbulenceLayers.values()) {
    layer.setOpacity(layer === visible ? overlayOpacity : 0)
    layer.setZIndex(layer === visible ? 6 : 5)
  }
  // A layer that was already there won't fire 'load' again, so restart the chain from it.
  if (existing) {
    if (existing.isLoading()) existing.once('load', preloadNext)
    else preloadNext()
  }
}

const playing = ref(false)
let playTimer: number | undefined
function togglePlay() {
  playing.value = !playing.value
  window.clearInterval(playTimer)
  if (playing.value) {
    playTimer = window.setInterval(() => {
      frameIndex.value = frames.value.length ? (frameIndex.value + 1) % frames.value.length : 0
    }, 800)
  }
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

function viewBounds() {
  const b = map!.getBounds()
  return {
    south: Math.max(b.getSouth(), -85),
    west: Math.max(b.getWest(), -180),
    north: Math.min(b.getNorth(), 85),
    east: Math.min(b.getEast(), 180)
  }
}

/** Polls every 15 s, or every 3 s while the server is still filling in the view, so planes appear as they arrive. */
async function refreshTraffic() {
  window.clearTimeout(trafficTimer)
  if (!map || !showAll.value) return
  const request = ++trafficRequest
  let complete = true
  try {
    const traffic = await fetchTraffic(viewBounds())
    if (request !== trafficRequest || !showAll.value) return
    const trackedHexes = new Set(tracked.value.map((t) => t.hex))
    planes.clearLayers()
    const size = planeSize()
    for (const a of traffic.aircraft) {
      if (trackedHexes.has(a.hex)) continue
      canvasPlane([a.lat, a.lon], a.category, a.trackDeg ?? 0, size, planeRenderer)
        .bindPopup(() => popupHtml(a))
        .addTo(planes)
    }
    aircraftCount.value = traffic.aircraft.length
    trafficComplete.value = complete = traffic.complete
  } catch (e) {
    console.warn('traffic refresh failed', e)
  }
  if (request === trafficRequest && showAll.value) {
    trafficTimer = window.setTimeout(refreshTraffic, complete ? 15000 : 3000)
  }
}

watch(showAll, (on) => {
  try {
    localStorage.setItem('turbol.showAllFlights', String(on))
  } catch {
    // private mode: the toggle just isn't remembered
  }
  if (on) refreshTraffic()
  else {
    window.clearTimeout(trafficTimer)
    trafficRequest++
    planes.clearLayers()
    aircraftCount.value = 0
  }
})

function drawTracked() {
  trackedPlanes.clearLayers()
  for (const t of tracked.value) {
    const a = t.aircraft
    if (!a) continue
    L.marker([a.lat, a.lon], {
      icon: planeIcon(a.category, a.trackDeg ?? 0, 28, true),
      zIndexOffset: 1000
    })
      .bindTooltip(t.label, {
        permanent: true,
        direction: 'right',
        offset: [12, 0],
        className: 'flight-label'
      })
      .bindPopup(popupHtml(a))
      .addTo(trackedPlanes)
  }
}

async function refreshTracked() {
  await Promise.all(
    tracked.value.map(async (t) => {
      try {
        t.aircraft = await fetchAircraft(t.hex)
      } catch (e) {
        console.warn('tracked flight refresh failed', t.hex, e)
      }
    })
  )
  drawTracked()
}

function syncUrl() {
  const flights = tracked.value.map((t) => t.query).join(',')
  router.replace({ query: { ...route.query, flights: flights || undefined } })
}

async function track(query: string, pan: boolean): Promise<string | undefined> {
  const lookup = await lookupFlight(query)
  const a = lookup.live
  if (!a) {
    return lookup.route
      ? `${lookup.callsign ?? query} isn't airborne right now.`
      : `Couldn't find a flight called ${query}.`
  }
  if (!tracked.value.some((t) => t.hex === a.hex)) {
    tracked.value.push({
      query,
      label: lookup.route?.iataCallsign ?? a.callsign ?? query,
      hex: a.hex,
      aircraft: a
    })
  }
  drawTracked()
  if (pan && map) map.setView([a.lat, a.lon], Math.max(map.getZoom(), 6))
  return undefined
}

async function addFlight() {
  const query = flightQuery.value.trim().toUpperCase()
  if (!query || adding.value) return
  adding.value = true
  addError.value = ''
  try {
    const error = await track(query, true)
    if (error) addError.value = error
    else {
      flightQuery.value = ''
      syncUrl()
    }
  } catch (e) {
    addError.value = `Lookup failed: ${(e as Error).message}`
  } finally {
    adding.value = false
  }
}

function removeFlight(hex: string) {
  tracked.value = tracked.value.filter((t) => t.hex !== hex)
  drawTracked()
  syncUrl()
}

function panTo(t: TrackedFlight) {
  if (t.aircraft && map) map.setView([t.aircraft.lat, t.aircraft.lon], Math.max(map.getZoom(), 6))
}

async function refreshStatus() {
  try {
    status.value = await fetchStatus()
    openedWithNowcast ??= status.value.nowcast?.id
  } catch (e) {
    console.warn('status refresh failed', e)
  } finally {
    statusLoaded.value = true
  }
}

watch([frames, frameIndex, levelFt], updateTurbulenceLayers)

onMounted(() => {
  map = L.map(mapEl.value!, { zoomControl: true, worldCopyJump: true }).setView([39.5, -97], 5)
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  updateTurbulenceLayers()
  planes.addTo(map)
  trackedPlanes.addTo(map)
  map.on('moveend', () => {
    if (showAll.value) refreshTraffic()
  })
  refreshStatus()
  statusTimer = window.setInterval(refreshStatus, 120000)
  if (showAll.value) refreshTraffic()

  const fromUrl = String(route.query.flights ?? '')
    .split(',')
    .map((q) => q.trim().toUpperCase())
    .filter(Boolean)
  Promise.all(fromUrl.map((q) => track(q, false).catch(() => undefined))).then(syncUrl)
  trackedTimer = window.setInterval(refreshTracked, 15000)
})

onBeforeUnmount(() => {
  window.clearTimeout(trafficTimer)
  window.clearInterval(trackedTimer)
  window.clearInterval(statusTimer)
  window.clearInterval(playTimer)
  trafficRequest++
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
  width: 260px;
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
.error-text {
  color: #fca5a5;
  margin-top: 4px;
}
.tracked {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
:deep(.flight-label) {
  font-weight: 700;
  padding: 1px 5px;
}
</style>
