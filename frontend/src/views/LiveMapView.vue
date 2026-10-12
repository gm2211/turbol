<template>
  <div class="map-page" :class="{ 'is-3d': mode === '3d', mobile }">
    <div ref="mapEl" class="map inspect-cursor" :style="{ '--inspect-cursor': inspectCursor }" />

    <component
      :is="Map3D"
      v-if="mode === '3d' && Map3D"
      ref="map3d"
      :view="view3d"
      :aircraft="aircraft3d"
      :frame="selectedFrame"
      :status="status"
      :level-ft="levelFt"
      :band="band"
      @move="on3dMove"
      @select="on3dSelect"
    />

    <v-btn-toggle
      v-show="!(mobile && (showControls || followHex))"
      :model-value="mode"
      mandatory
      density="compact"
      theme="dark"
      color="var(--my-accent)"
      class="mode-toggle glass-panel"
      @update:model-value="setMode"
    >
      <v-btn value="2d" :size="mobile ? 'default' : 'small'" :loading="loading3d && mode === '2d'"
        >2D</v-btn
      >
      <v-btn
        value="3d"
        :size="mobile ? 'default' : 'small'"
        :title="mobile ? 'Or slide up with two fingers' : 'Or shift+drag the map'"
        >3D</v-btn
      >
    </v-btn-toggle>

    <v-btn
      v-if="trafficMode === 'sample' && areaMoved"
      class="search-area glass-panel"
      theme="dark"
      prepend-icon="mdi-refresh"
      rounded="pill"
      @click="searchThisArea"
      >Search this area</v-btn
    >

    <div class="panel glass-panel top-left">
      <div class="panel-title">Live turbulence</div>
      <div class="muted" v-if="status?.nowcast && !mobile">
        GTG-N nowcast valid {{ formatUtc(status.nowcast.validTime) }}
      </div>
      <div class="muted" v-if="status?.global && !mobile">
        Outside the dashed line: {{ globalSource }}, ~{{
          Math.round(status.global.resolutionKm)
        }}
        km, {{ formatUtc(status.global.issued) }} cycle
      </div>
      <div class="muted" v-else-if="statusLoaded && !status?.nowcast">Turbulence data loading…</div>

      <div class="flights-row mt-2">
        <span class="muted">Flights</span>
        <v-btn-toggle
          v-model="trafficMode"
          mandatory
          density="compact"
          theme="dark"
          color="var(--my-accent)"
          variant="outlined"
          divided
        >
          <v-btn value="off" size="small">None</v-btn>
          <v-btn
            value="sample"
            size="small"
            :title="`${sampleSize} random flights, reshuffled every 30 s`"
            >{{ sampleSize }} random</v-btn
          >
          <v-btn value="all" size="small">All</v-btn>
        </v-btn-toggle>
      </div>
      <div class="muted" v-if="trafficMode !== 'off'">
        <template v-if="!trafficComplete && aircraftCount === 0">Loading flights…</template>
        <template v-else-if="trafficMode === 'sample'">
          {{ shownCount.toLocaleString() }} of {{ aircraftCount.toLocaleString() }} aircraft in this
          area
        </template>
        <template v-else>{{ shownCount.toLocaleString() }} aircraft in view</template>
        <span
          v-if="
            aircraftCount > 0 &&
            (!trafficComplete || shownCount < Math.min(targetCount, aircraftCount))
          "
        >
          (loading more)</span
        >
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
          :class="{ following: t.hex === followHex }"
          @click="follow(t.hex)"
          @click:close="removeFlight(t.hex)"
        >
          {{ t.label }}<span v-if="!t.aircraft">&nbsp;(landed?)</span>
        </v-chip>
      </div>
    </div>

    <v-btn
      v-if="mobile && !showControls"
      icon="mdi-tune-variant"
      theme="dark"
      class="glass-panel controls-fab"
      aria-label="Forecast time"
      @click="showControls = true"
    />
    <div
      v-if="!mobile || showControls"
      class="panel glass-panel top-right controls"
      :class="{ sheet: mobile }"
    >
      <div v-if="mobile" class="d-flex align-center justify-space-between mb-2">
        <span class="panel-title">Forecast time</span>
        <v-btn
          icon="mdi-close"
          size="small"
          variant="text"
          theme="dark"
          aria-label="Close"
          @click="showControls = false"
        />
      </div>
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

    <AltitudeRail
      v-show="!(mobile && showControls)"
      class="altitude-rail"
      :range="mode === '3d'"
      :level="levelFt"
      :band="band"
      :compact="mobile"
      @update:level="levelFt = $event"
      @update:band="band = $event"
    />

    <EdrHover
      :map="leafletMap"
      :frame-id="selectedFrame?.id"
      :level-ft="levelFt"
      :level-label="levelLabel"
    />

    <FollowStrip
      v-if="followHex"
      :hex="followHex"
      :mobile="mobile"
      @update="onFollowUpdate"
      @close="unfollow"
    />

    <div v-show="!followHex" class="bottom-left">
      <TurbulenceLegend
        :title="`Turbulence at ${levelLabel}`"
        :compact="mobile"
        :aircraft-key="trafficMode !== 'off' || tracked.length > 0 || !!followHex"
        :coarse-km="status?.global?.resolutionKm"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import {
  type Component,
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TurbulenceLegend from '@/components/turbulence/TurbulenceLegend.vue'
import AltitudeRail from '@/components/turbulence/AltitudeRail.vue'
import EdrHover from '@/components/turbulence/EdrHover.vue'
import { inspectCursor } from '@/components/turbulence/mapCursor'
import FollowStrip from '@/components/turbulence/FollowStrip.vue'
import { canvasPlane, planeIcon, updateCanvasPlane } from '@/components/turbulence/planeIcon'
import {
  type AircraftView,
  type Bounds,
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
import type { LiveFlightUpdate } from '@/api/follow'
import { baseMapAttribution, baseMapUrl } from '@/components/turbulence/baseMap'
import { goodConnection, onConnectionChange } from '@/util/network'
import { useMobile } from '@/util/useMobile'
import type { MapView } from '@/components/turbulence/Map3D.vue'

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
const globalSource = computed(() =>
  status.value?.global?.wafsIssued ? 'WAFS + NOAA GFS' : 'NOAA GFS-derived'
)
const statusLoaded = ref(false)
const levelFt = ref(35000)
const frameIndex = ref(0)
const aircraftCount = ref(0)
const trafficComplete = ref(false)
type TrafficMode = 'off' | 'sample' | 'all'
const trafficMode = ref<TrafficMode>(readTrafficMode())
const sampleSize = 100
const sampleRotateMs = 30000
const shownCount = ref(0)
const targetCount = computed(() => (trafficMode.value === 'sample' ? sampleSize : Infinity))
/** In sample mode, flights come from the area searched last; moving the map offers "Search this area". */
const areaMoved = ref(false)
let searchedBounds: Bounds | undefined
const flightQuery = ref('')
const adding = ref(false)
const addError = ref('')
const tracked = ref<TrackedFlight[]>([])
const mobile = useMobile()
const showControls = ref(false)

function readTrafficMode(): TrafficMode {
  try {
    const saved = localStorage.getItem('turbol.trafficMode')
    if (saved === 'off' || saved === 'sample' || saved === 'all') return saved
    return localStorage.getItem('turbol.showAllFlights') === 'true' ? 'all' : 'off'
  } catch {
    return 'off'
  }
}

const levelLabel = computed(() => flightLevel(levelFt.value))
/** Levels the 3D view shows: around the map altitude until the rail's band is moved; the full range is every level. */
const band = ref<[number, number]>([levelFt.value - 4000, levelFt.value + 4000])

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
const leafletMap = shallowRef<L.Map>()
const planeRenderer = L.canvas({ padding: 0.2 })
const planes = L.layerGroup()
const trackedPlanes = L.layerGroup()
/** What the 3D mode draws: every flight in view when "Show all flights" is on, plus the added flights. */
const traffic = shallowRef<AircraftView[]>([])
const trafficMarkers = new Map<string, L.CircleMarker>()
const trackedMarkers = new Map<string, L.Marker>()
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
 * the time slider or playing it only swaps which layer is visible. Preloading only runs on a good connection; on a
 * slow one each hour loads when picked. Frame ids are immutable, so the browser caches tiles; a status refresh drops
 * hours that have passed, and a new nowcast or forecast cycle brings new ids (and new layers).
 */
const turbulenceLayers = new Map<string, L.TileLayer>()
const overlayOpacity = 0.85
let preloadGeneration = 0

function addTurbulenceLayer(url: string, onLoaded?: () => void): L.TileLayer {
  const layer = L.tileLayer(url, {
    opacity: 0,
    maxNativeZoom: 9,
    zIndex: 5,
    attribution: 'Turbulence: NOAA GTG-N / GTG (US, 3 km), WAFS (AWC) + NOAA GFS elsewhere (~28 km)'
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
    if (generation !== preloadGeneration || !goodConnection()) return
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
  const color = categoryColors[a.category]
  const edr = a.edr != null ? `<span class="pp-edr">EDR ${a.edr.toFixed(2)}</span>` : ''
  const q = a.callsign ? encodeURIComponent(a.callsign) : undefined
  const action = (href: string, icon: string, label: string, followHex?: string) =>
    `<a class="pp-action" href="${href}" data-route="${href}"${followHex ? ` data-follow="${followHex}"` : ''}>` +
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
    action(`/map?follow=${a.hex}`, 'mdi-crosshairs-gps', 'Follow', a.hex) +
    (q
      ? action(`/analyze?q=${q}`, 'mdi-airplane-search', 'Analyze') +
        action(`/map?view=3d&flights=${q}`, 'mdi-cube-outline', '3D')
      : '') +
    `</div></div>`
  )
}

/** Popup buttons are plain HTML, so route their clicks through the SPA router instead of reloading the page. */
function onPopupClick(e: MouseEvent) {
  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
  const link = (e.target as HTMLElement).closest<HTMLAnchorElement>('a[data-route]')
  if (!link) return
  e.preventDefault()
  if (link.dataset.follow) {
    map?.closePopup()
    follow(link.dataset.follow)
  } else router.push(link.dataset.route!)
}

const popupOptions: L.PopupOptions = { className: 'plane-popup', closeButton: true, minWidth: 220 }

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

let sample: { hexes: string[]; at: number } | undefined

/** Keeps the current sample while it's fresh (topping it up when flights leave), and draws a new one every 30 s. */
function pickSample(all: AircraftView[]): AircraftView[] {
  const byHex = new Map(all.map((a) => [a.hex, a]))
  const fresh = sample && Date.now() - sample.at < sampleRotateMs
  const kept = fresh ? sample!.hexes.filter((h) => byHex.has(h)) : []
  const keptSet = new Set(kept)
  const rest = all.filter((a) => !keptSet.has(a.hex))
  // Partial Fisher-Yates: only as many swaps as picks.
  const need = Math.min(sampleSize - kept.length, rest.length)
  for (let i = 0; i < need; i++) {
    const j = i + Math.floor(Math.random() * (rest.length - i))
    ;[rest[i], rest[j]] = [rest[j]!, rest[i]!]
  }
  const hexes = [...kept, ...rest.slice(0, need).map((a) => a.hex)]
  sample = { hexes, at: fresh ? sample!.at : Date.now() }
  return hexes.map((h) => byHex.get(h)!)
}

/**
 * Polls every 15 s (every 3 s while the server is still filling in the area). Each poll is applied by
 * `applyTraffic`, which spreads the marker work over animation frames so the page never freezes.
 */
async function refreshTraffic() {
  window.clearTimeout(trafficTimer)
  if (!map || trafficMode.value === 'off') return
  const request = ++trafficRequest
  let complete = true
  try {
    const bounds = trafficMode.value === 'sample' ? (searchedBounds ??= viewBounds()) : viewBounds()
    const response = await fetchTraffic(bounds)
    // Switching modes bumps trafficRequest, so a stale response is dropped here.
    if (request !== trafficRequest) return
    const shown = trafficMode.value === 'sample' ? pickSample(response.aircraft) : response.aircraft
    aircraftCount.value = response.aircraft.length
    trafficComplete.value = complete = response.complete
    applyTraffic(shown)
  } catch (e) {
    console.warn('traffic refresh failed', e)
  }
  if (request === trafficRequest) {
    trafficTimer = window.setTimeout(refreshTraffic, complete ? 15000 : 3000)
  }
}

const trafficData = new Map<string, AircraftView>()
let applyJob = 0

/**
 * Moves planes that are still there, drops the ones that left and adds new ones, nearest the centre first, within
 * ~6 ms per animation frame. The 3D view gets the growing list a few times a second rather than every frame.
 */
function applyTraffic(list: AircraftView[]) {
  if (!map) return
  const job = ++applyJob
  const trackedHexes = new Set(tracked.value.map((t) => t.hex))
  if (followHex.value) trackedHexes.add(followHex.value)
  const wanted = new Map(list.filter((a) => !trackedHexes.has(a.hex)).map((a) => [a.hex, a]))
  for (const [hex, marker] of trafficMarkers) {
    if (!wanted.has(hex)) {
      planes.removeLayer(marker)
      trafficMarkers.delete(hex)
      trafficData.delete(hex)
    }
  }
  const c = map.getCenter()
  const pending = [...wanted.values()].sort(
    (a, b) => Math.hypot(a.lat - c.lat, a.lon - c.lng) - Math.hypot(b.lat - c.lat, b.lon - c.lng)
  )
  const size = planeSize()
  let i = 0
  let published = 0
  const publish = () => {
    traffic.value = [...trafficData.values()]
    shownCount.value = trafficMarkers.size
    published = performance.now()
  }
  const step = () => {
    if (job !== applyJob) return
    const until = performance.now() + 6
    while (i < pending.length && performance.now() < until) {
      for (const end = Math.min(i + 25, pending.length); i < end; i++) {
        const a = pending[i]!
        trafficData.set(a.hex, a)
        const marker = trafficMarkers.get(a.hex)
        if (marker) updateCanvasPlane(marker, [a.lat, a.lon], a.category, a.trackDeg ?? 0, size)
        else {
          const hex = a.hex
          trafficMarkers.set(
            hex,
            canvasPlane([a.lat, a.lon], a.category, a.trackDeg ?? 0, size, planeRenderer)
              .bindPopup(() => popupHtml(trafficData.get(hex) ?? a), popupOptions)
              .addTo(planes)
          )
        }
      }
    }
    if (i < pending.length) {
      if (performance.now() - published > 250) publish()
      requestAnimationFrame(step)
    } else publish()
  }
  requestAnimationFrame(step)
}

function clearTraffic() {
  applyJob++
  planes.clearLayers()
  trafficMarkers.clear()
  trafficData.clear()
  traffic.value = []
  shownCount.value = 0
  aircraftCount.value = 0
  trafficComplete.value = false
}

function searchThisArea() {
  searchedBounds = viewBounds()
  sample = undefined
  areaMoved.value = false
  refreshTraffic()
}

watch(trafficMode, (m) => {
  try {
    localStorage.setItem('turbol.trafficMode', m)
  } catch {
    // private mode: the choice just isn't remembered
  }
  window.clearTimeout(trafficTimer)
  trafficRequest++
  clearTraffic()
  sample = undefined
  searchedBounds = undefined
  areaMoved.value = false
  if (m !== 'off') refreshTraffic()
})

function drawTracked() {
  trackedPlanes.clearLayers()
  trackedMarkers.clear()
  for (const t of tracked.value) {
    const a = t.aircraft
    if (!a || t.hex === followHex.value) continue
    const marker = L.marker([a.lat, a.lon], {
      icon: planeIcon(a.category, a.trackDeg ?? 0, 28, true),
      zIndexOffset: 1000
    })
      .bindTooltip(t.label, {
        permanent: true,
        direction: 'right',
        offset: [12, 0],
        className: 'flight-label'
      })
      .bindPopup(popupHtml(a), popupOptions)
      .addTo(trackedPlanes)
    trackedMarkers.set(a.hex, marker)
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
  lastTrackedHex = a.hex
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
      if (lastTrackedHex) follow(lastTrackedHex)
      syncUrl()
    }
  } catch (e) {
    addError.value = `Lookup failed: ${(e as Error).message}`
  } finally {
    adding.value = false
  }
}

function removeFlight(hex: string) {
  if (hex === followHex.value) unfollow()
  tracked.value = tracked.value.filter((t) => t.hex !== hex)
  drawTracked()
  syncUrl()
}

// Following one flight: a strip along the bottom of the map with its live status, and on the map its trail so far
// and the next 90 minutes (or the rest of its route) drawn as a turbulence ribbon. The followed flight is in the URL
// (?follow=<hex>) so a followed view can be shared, and old /follow/<hex> links land here.
const followHex = ref<string>()
const followLayer = L.layerGroup()
let followTrail: [number, number][] = []
let followCentered = false
let followLevelSet = false
let lastTrackedHex: string | undefined
let followMarker: L.Marker | undefined

function follow(hex: string) {
  if (hex === followHex.value) {
    if (followMarker && map) map.setView(followMarker.getLatLng(), Math.max(map.getZoom(), 7))
    return
  }
  followHex.value = hex
  followTrail = []
  followCentered = false
  followLevelSet = false
  followLayer.clearLayers()
  followMarker = undefined
  router.replace({ query: { ...route.query, follow: hex } })
  drawTracked()
  if (trafficMode.value !== 'off') refreshTraffic()
}

function unfollow() {
  followHex.value = undefined
  followLayer.clearLayers()
  followMarker = undefined
  router.replace({ query: { ...route.query, follow: undefined } })
  drawTracked()
  if (trafficMode.value !== 'off') refreshTraffic()
}

function onFollowUpdate(u: LiveFlightUpdate) {
  const a = u.aircraft
  if (!map || !a || u.aircraft?.hex !== followHex.value) return
  const last = followTrail.at(-1)
  if (!last || last[0] !== a.lat || last[1] !== a.lon) followTrail.push([a.lat, a.lon])
  followLayer.clearLayers()
  // One polyline per run of same-category points, so the path ahead reads as a turbulence ribbon.
  const pts = u.ahead?.points ?? []
  let start = 0
  for (let i = 1; i <= pts.length; i++) {
    if (i === pts.length || pts[i]!.category !== pts[start]!.category) {
      const run = pts
        .slice(start, Math.min(i + 1, pts.length))
        .map((p) => [p.lat, p.lon] as [number, number])
      L.polyline(run, {
        color: categoryColors[pts[start]!.category],
        weight: 5,
        opacity: 0.9
      }).addTo(followLayer)
      start = i
    }
  }
  if (followTrail.length > 1) {
    L.polyline(followTrail, { color: '#334155', weight: 3, dashArray: '4 6', opacity: 0.8 }).addTo(
      followLayer
    )
  }
  const category = u.upcoming?.nowCategory ?? 'NoData'
  const label = u.route?.iataCallsign ?? a.callsign ?? a.hex
  followMarker = L.marker([a.lat, a.lon], {
    icon: planeIcon(category, a.trackDeg ?? 0, 30, true),
    zIndexOffset: 2000
  })
    .bindTooltip(label, {
      permanent: true,
      direction: 'right',
      offset: [14, 0],
      className: 'flight-label'
    })
    .addTo(followLayer)
  // Show the turbulence at the flight's own altitude, once, so the ribbon and the map agree.
  if (!followLevelSet && a.altitudeFt !== undefined) {
    followLevelSet = true
    // The rail moves in 1,000 ft steps.
    levelFt.value = Math.min(Math.max(Math.round(a.altitudeFt / 1000) * 1000, 1000), 45000)
  }
  // Centre on the plane first, then only move the map when it nears the edge, so panning around isn't fought.
  const inner = map.getBounds().pad(-0.25)
  if (!followCentered) {
    followCentered = true
    map.setView([a.lat, a.lon], Math.max(map.getZoom(), 7))
  } else if (!inner.contains([a.lat, a.lon])) map.panTo([a.lat, a.lon])
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

// GTG's edge: 3 km inside, the coarse GFS-derived layer outside.
let gtgOutline: L.Polyline | undefined
watch(
  () => status.value?.gtgOutline,
  (ring) => {
    if (!map || !ring?.length || gtgOutline) return
    gtgOutline = L.polyline(ring, {
      color: '#1e293b',
      weight: 2,
      opacity: 0.8,
      dashArray: '8 6',
      interactive: false
    }).addTo(map)
  }
)
const stopConnectionWatch = onConnectionChange(() => {
  if (goodConnection()) updateTurbulenceLayers()
})

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
const aircraft3d = computed<AircraftView[]>(() => {
  const added = tracked.value.flatMap((t) => (t.aircraft ? [t.aircraft] : []))
  const addedHexes = new Set(added.map((a) => a.hex))
  return [...added, ...traffic.value.filter((a) => !addedHexes.has(a.hex))]
})

async function loadMap3D() {
  Map3D.value ??= (await import('@/components/turbulence/Map3D.vue')).default
}

async function enter3d(pitch = 0, bearing = 0) {
  if (!map || mode.value === '3d') return
  const c = map.getCenter()
  view3d.value = { lat: c.lat, lon: c.lng, zoom: map.getZoom(), pitch, bearing }
  if (!Map3D.value) {
    loading3d.value = true
    try {
      await loadMap3D()
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
  const marker = trackedMarkers.get(hex) ?? trafficMarkers.get(hex)
  if (marker && map) {
    map.panTo(marker.getLatLng(), { animate: false })
    marker.openPopup()
  }
}

/**
 * "?view=3d" (old /3d links, the popup's and Analyze's 3D buttons) opens 3D, adding any "?flights=" not yet
 * on the map. On this page already, the router only changes the query, so it's watched rather than read on mount.
 */
watch(
  () => route.query.view,
  async (view) => {
    if (view !== '3d') return
    const wanted = String(route.query.flights ?? '')
      .split(',')
      .map((q) => q.trim().toUpperCase())
      .filter((q) => q && !tracked.value.some((t) => t.query === q))
    await Promise.all(wanted.map((q) => track(q, true).catch(() => undefined)))
    if (wanted.length) syncUrl()
    await setMode('3d')
    router.replace({ query: { ...route.query, view: undefined } })
  }
)

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

// On touch screens, two fingers sliding up together (not pinching) opens 3D, like tilting a phone map.
let twoFinger: { y: number; gap: number } | undefined
const touchGap = (t: TouchList) =>
  Math.hypot(t[0]!.clientX - t[1]!.clientX, t[0]!.clientY - t[1]!.clientY)
const touchY = (t: TouchList) => (t[0]!.clientY + t[1]!.clientY) / 2
function onTouchStart(e: TouchEvent) {
  twoFinger =
    mode.value === '2d' && e.touches.length === 2
      ? { y: touchY(e.touches), gap: touchGap(e.touches) }
      : undefined
}
function onTouchMove(e: TouchEvent) {
  if (!twoFinger || e.touches.length !== 2) return
  const dy = touchY(e.touches) - twoFinger.y
  const pinch = Math.abs(touchGap(e.touches) - twoFinger.gap)
  if (dy < -30 && pinch < 25) {
    twoFinger = undefined
    setMode('3d')
  } else if (pinch > 40) twoFinger = undefined
}

onMounted(() => {
  // boxZoom off: shift+drag opens 3D instead.
  map = L.map(mapEl.value!, { zoomControl: true, worldCopyJump: true, boxZoom: false }).setView(
    [39.5, -97],
    5
  )
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  updateTurbulenceLayers()
  planes.addTo(map)
  leafletMap.value = map
  mapEl.value!.addEventListener('click', onPopupClick)
  mapEl.value!.addEventListener('pointerdown', onShiftDrag)
  mapEl.value!.addEventListener('touchstart', onTouchStart, { passive: true })
  mapEl.value!.addEventListener('touchmove', onTouchMove, { passive: true })
  trackedPlanes.addTo(map)
  followLayer.addTo(map)
  map.on('moveend', () => {
    if (trafficMode.value === 'all') refreshTraffic()
    else if (trafficMode.value === 'sample') areaMoved.value = true
  })
  refreshStatus()
  statusTimer = window.setInterval(refreshStatus, 120000)
  if (trafficMode.value !== 'off') refreshTraffic()

  const fromUrl = String(route.query.flights ?? '')
    .split(',')
    .map((q) => q.trim().toUpperCase())
    .filter(Boolean)
  Promise.all(fromUrl.map((q) => track(q, false).catch(() => undefined))).then(() => {
    syncUrl()
    if (route.query.view === '3d') {
      setMode('3d')
      router.replace({ query: { ...route.query, view: undefined } })
    }
  })
  const followFromUrl = String(route.query.follow ?? '').trim()
  if (followFromUrl) follow(followFromUrl)
  trackedTimer = window.setInterval(refreshTracked, 15000)
  // Fetch the 3D code once the 2D map is up and the browser is idle, so opening 3D doesn't wait on it.
  const warm3d = () => {
    if (goodConnection()) loadMap3D().catch(() => undefined)
  }
  // Safari has no requestIdleCallback.
  if (typeof window.requestIdleCallback === 'function')
    window.requestIdleCallback(warm3d, { timeout: 5000 })
  else setTimeout(warm3d, 3000)
})

onBeforeUnmount(() => {
  window.clearTimeout(trafficTimer)
  window.clearInterval(trackedTimer)
  window.clearInterval(statusTimer)
  window.clearInterval(playTimer)
  trafficRequest++
  mapEl.value?.removeEventListener('click', onPopupClick)
  stopConnectionWatch()
  map?.remove()
})
</script>

<style>
/* Inspect cursor instead of Leaflet's open hand; grabbing while dragging, pointer over planes stays. */
.inspect-cursor.leaflet-grab {
  cursor: var(--inspect-cursor);
}
.leaflet-dragging .inspect-cursor.leaflet-grab {
  cursor: grabbing;
}
</style>

<style scoped>
.map-page {
  position: relative;
  width: 100%;
  height: var(--page-h);
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
.altitude-rail {
  position: absolute;
  z-index: 1000;
  right: 12px;
  top: 150px;
  bottom: 72px;
  max-height: 420px;
}
.mobile .altitude-rail {
  right: 8px;
  top: 60px;
  bottom: 104px;
}
.mode-toggle {
  position: absolute;
  z-index: 1000;
  right: 12px;
  bottom: 24px;
}
/* Phones: panels hug the edges, settings open as a bottom sheet, pinch replaces the zoom buttons. */
.mobile .top-left {
  top: 8px;
  left: 8px;
  /* Clear of the flight-level rail. */
  right: 92px;
  width: auto;
  padding: 6px 10px;
  font-size: 12px;
}
.mobile .top-left .panel-title {
  font-size: 13px;
}
.controls-fab {
  position: absolute;
  z-index: 1000;
  top: 8px;
  right: 8px;
}
.top-right.sheet {
  top: auto;
  left: 8px;
  right: 8px;
  bottom: 8px;
  width: auto;
  z-index: 1100;
  padding: 12px 16px 16px;
  background: rgba(24, 39, 52, 0.92);
}
.mobile .bottom-left {
  bottom: 22px;
  left: 8px;
}
.mobile .mode-toggle {
  right: 8px;
  bottom: 56px;
}
.mobile :deep(.leaflet-control-zoom) {
  display: none;
}
.mobile :deep(.leaflet-control-attribution) {
  font-size: 9px;
  max-width: calc(100vw - 24px);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* The 2D map's zoom buttons and EDR readout belong to the hidden map in 3D (3D has its own tooltip). */
.is-3d :deep(.leaflet-control-container),
.is-3d :deep(.edr-tip) {
  display: none;
}
.flights-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search-area {
  position: absolute;
  z-index: 1000;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
}
.error-text {
  color: #fca5a5;
  margin-top: 4px;
}
.tracked :deep(.v-chip.following) {
  outline: 2px solid #fff;
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
  transition:
    background 0.15s,
    transform 0.1s;
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
