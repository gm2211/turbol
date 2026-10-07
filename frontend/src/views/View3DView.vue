<template>
  <div class="map-page">
    <div ref="mapEl" class="map" @contextmenu.prevent />

    <div class="panel glass-panel top-left">
      <div class="panel-title">3D turbulence</div>
      <div class="muted">{{ selectedFrameLabel }}</div>
      <div class="muted" v-if="volume">
        {{ voxelCount.toLocaleString() }} rough blocks shown · {{ Math.round(volume.cellKm) }} km
        grid
      </div>
      <div class="muted" v-else-if="loadingVolume">Loading the turbulence volume…</div>
      <div class="d-flex align-center ga-2 mt-2">
        <v-text-field
          v-model="flightQuery"
          label="Flight (UA1517)"
          density="compact"
          variant="outlined"
          hide-details
          theme="dark"
          class="flight-field"
          @keyup.enter="loadFlight"
        />
        <v-btn color="#10324f" :loading="loadingFlight" @click="loadFlight">Show</v-btn>
      </div>
      <div class="muted mt-1" v-if="flightLabel">{{ flightLabel }}</div>
      <v-alert v-if="error" type="warning" variant="tonal" density="compact" class="mt-2">{{
        error
      }}</v-alert>
      <div class="hint mt-1">
        Drag to pan · right-drag or Ctrl+drag to tilt and rotate · click for the column
      </div>
    </div>

    <div class="panel glass-panel top-right controls">
      <div class="text-caption">
        Flight level: <b>{{ flightLevel(levelFt) }}</b>
      </div>
      <v-slider
        v-model="levelFt"
        :min="1000"
        :max="45000"
        :step="1000"
        hide-details
        density="compact"
        theme="dark"
        color="var(--my-accent)"
      />
      <v-select
        v-model="windowFt"
        :items="windowItems"
        label="Show above and below"
        density="compact"
        hide-details
        variant="outlined"
        theme="dark"
        class="my-3"
      />
      <div class="text-caption">{{ selectedFrameShort }}</div>
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
      <div class="text-caption">Vertical exaggeration: ×{{ exaggeration }}</div>
      <v-slider
        v-model="exaggeration"
        :min="5"
        :max="80"
        :step="5"
        hide-details
        density="compact"
        theme="dark"
        color="var(--my-accent)"
      />
      <v-switch
        v-model="showLight"
        label="Show light turbulence"
        density="compact"
        class="light-switch"
        hide-details
        theme="dark"
        color="var(--my-accent)"
      />
    </div>

    <div class="panel glass-panel right-profile" v-if="column">
      <v-btn
        icon="mdi-close"
        size="x-small"
        variant="text"
        theme="dark"
        class="close"
        @click="column = undefined"
      />
      <ColumnProfile
        :column="column"
        :level-ft="levelFt"
        :window="windowFt >= 60000 ? 45000 : windowFt"
      />
    </div>

    <div class="bottom-left">
      <TurbulenceLegend title="Turbulence (medium aircraft)" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Deck, type Layer, type PickingInfo, WebMercatorViewport } from '@deck.gl/core'
import {
  BitmapLayer,
  LineLayer,
  PathLayer,
  ScatterplotLayer,
  SolidPolygonLayer
} from '@deck.gl/layers'
import { TileLayer } from '@deck.gl/geo-layers'
import { SimpleMeshLayer } from '@deck.gl/mesh-layers'
import { CubeGeometry } from '@luma.gl/engine'
import TurbulenceLegend from '@/components/turbulence/TurbulenceLegend.vue'
import ColumnProfile from '@/components/turbulence/ColumnProfile.vue'
import { baseMapUrl } from '@/components/turbulence/baseMap'
import {
  type AircraftView,
  type FrameInfo,
  type TurbulenceCategory,
  type TurbulenceColumn,
  type TurbulenceStatus,
  type TurbulenceVolume,
  categoryColors,
  categoryLabels,
  classify,
  fetchColumn,
  fetchStatus,
  fetchVolume,
  flightLevel,
  formatUtc,
  overlayRgba
} from '@/api/turbulence'
import { type RouteAnalysis, type RoutePoint, analyzeFlight, lookupFlight } from '@/api/flights'

interface Segment {
  from: RoutePoint
  to: RoutePoint
}

interface Voxel {
  lat: number
  lon: number
  levelFt: number
  bottomFt: number
  topFt: number
  edr: number
  category: TurbulenceCategory
}

const route = useRoute()
const router = useRouter()
const mapEl = ref<HTMLDivElement>()
const status = ref<TurbulenceStatus>()
const volume = shallowRef<TurbulenceVolume>()
const voxels = shallowRef<Voxel[]>([])
const column = ref<TurbulenceColumn>()
const analysis = shallowRef<RouteAnalysis>()
const live = ref<AircraftView>()
const flightQuery = ref('')
const flightLabel = ref('')
const error = ref<string>()
const loadingVolume = ref(false)
const loadingFlight = ref(false)
const levelFt = ref(35000)
const windowFt = ref(4000)
const frameIndex = ref(0)
const exaggeration = ref(30)
const showLight = ref(true)

const windowItems = [
  { title: '± 2,000 ft', value: 2000 },
  { title: '± 4,000 ft', value: 4000 },
  { title: '± 8,000 ft', value: 8000 },
  { title: 'All levels', value: 60000 }
]

const ftToM = 0.3048
const z = (ft: number) => ft * ftToM * exaggeration.value
const blue: [number, number, number] = [16, 50, 79] // --my-blue

/** Nowcast first, then forecast hours that are still in the future (same as the live map). */
const frames = computed<FrameInfo[]>(() => {
  const s = status.value
  if (!s) return []
  const now = Date.now()
  const future = s.forecast.filter((f) => new Date(f.validTime).getTime() > now + 15 * 60 * 1000)
  return [...(s.nowcast ? [s.nowcast] : []), ...future]
})
const selectedFrame = computed(() => frames.value[frameIndex.value])
const selectedFrameShort = computed(() => {
  const f = selectedFrame.value
  if (!f) return 'No turbulence data yet'
  if (f.kind === 'nowcast') return `Now (${formatUtc(f.validTime)})`
  const hours = Math.round((new Date(f.validTime).getTime() - Date.now()) / 3600000)
  return `Forecast +${hours}h (${formatUtc(f.validTime)})`
})
const selectedFrameLabel = computed(() => {
  const f = selectedFrame.value
  if (!f) return 'Turbulence data loading…'
  return f.kind === 'nowcast'
    ? `GTG-N nowcast valid ${formatUtc(f.validTime)}`
    : `GTG forecast valid ${formatUtc(f.validTime)}`
})

const visibleVoxels = computed(() => {
  const lo = levelFt.value - windowFt.value
  const hi = levelFt.value + windowFt.value
  return voxels.value.filter(
    (v) => v.topFt >= lo && v.bottomFt <= hi && (showLight.value || v.category !== 'Light')
  )
})
const voxelCount = computed(() => visibleVoxels.value.length)

function toVoxels(v: TurbulenceVolume): Voxel[] {
  const thresholds = status.value?.thresholds.Medium
  if (!thresholds) return []
  const levels = v.levelsFt
  const out: Voxel[] = []
  for (let k = 0; k < v.voxels.length; k += 4) {
    const i = v.voxels[k + 2]!
    const level = levels[i]!
    const prev = levels[i - 1]
    const next = levels[i + 1]
    const edr = v.voxels[k + 3]! / 200
    out.push({
      lat: v.voxels[k]! / 100,
      lon: v.voxels[k + 1]! / 100,
      levelFt: level,
      bottomFt: prev === undefined ? Math.max(level - 1000, 0) : (prev + level) / 2,
      topFt: next === undefined ? level + 1000 : (next + level) / 2,
      edr,
      category: classify(edr, thresholds)
    })
  }
  return out
}

let deck: Deck | undefined
const cube = new CubeGeometry()

function hexRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function buildLayers(): Layer[] {
  const layers: Layer[] = [
    new TileLayer({
      id: 'basemap',
      data: baseMapUrl,
      minZoom: 0,
      maxZoom: 12,
      tileSize: 256,
      renderSubLayers: (props) => {
        const [[west, south], [east, north]] = props.tile.boundingBox
        return new BitmapLayer(props, {
          data: undefined,
          image: props.data,
          bounds: [west, south, east, north]
        })
      }
    })
  ]

  // Translucent sheet at the chosen flight level.
  const c = status.value?.coverage
  if (c) {
    const h = z(levelFt.value)
    layers.push(
      new SolidPolygonLayer({
        id: 'level-sheet',
        data: [
          [
            [c.west, c.south, h],
            [c.east, c.south, h],
            [c.east, c.north, h],
            [c.west, c.north, h]
          ]
        ],
        getPolygon: (d) => d,
        getFillColor: [...blue, 22],
        parameters: { depthWriteEnabled: false }
      }),
      new PathLayer({
        id: 'level-outline',
        data: [
          [
            [c.west, c.south, h],
            [c.east, c.south, h],
            [c.east, c.north, h],
            [c.west, c.north, h],
            [c.west, c.south, h]
          ]
        ],
        getPath: (d) => d,
        getColor: [...blue, 160],
        getWidth: 1.5,
        widthUnits: 'pixels'
      })
    )
  }

  if (volume.value) {
    const halfM = (volume.value.cellKm * 1000) / 2
    layers.push(
      new SimpleMeshLayer<Voxel>({
        id: 'voxels',
        data: visibleVoxels.value,
        mesh: cube,
        getPosition: (v) => [v.lon, v.lat, z((v.bottomFt + v.topFt) / 2)],
        getScale: (v) => [halfM * 0.92, halfM * 0.92, (z(v.topFt) - z(v.bottomFt)) / 2],
        getColor: (v) => overlayRgba[v.category],
        material: { ambient: 0.6, diffuse: 0.5, shininess: 8, specularColor: [40, 40, 40] },
        pickable: true,
        updateTriggers: { getPosition: exaggeration.value, getScale: exaggeration.value }
      })
    )
  }

  const a = analysis.value
  if (a && a.points.length > 1) {
    const pts = a.points
    const segments: Segment[] = pts.slice(1).map((p, i) => ({ from: pts[i]!, to: p }))
    layers.push(
      new SolidPolygonLayer<Segment>({
        id: 'flight-curtain',
        data: segments,
        _full3d: true,
        getPolygon: (s) => [
          [s.from.lon, s.from.lat, 0],
          [s.to.lon, s.to.lat, 0],
          [s.to.lon, s.to.lat, z(s.to.altitudeFt)],
          [s.from.lon, s.from.lat, z(s.from.altitudeFt)]
        ],
        getFillColor: [...blue, 30],
        parameters: { depthWriteEnabled: false },
        updateTriggers: { getPolygon: exaggeration.value }
      }),
      new PathLayer<Segment>({
        id: 'flight-path',
        data: segments,
        getPath: (s) => [
          [s.from.lon, s.from.lat, z(s.from.altitudeFt)],
          [s.to.lon, s.to.lat, z(s.to.altitudeFt)]
        ],
        getColor: (s) => {
          const cat = s.from.category === 'NoData' ? 'Smooth' : s.from.category
          return [...hexRgb(categoryColors[cat]), 255]
        },
        getWidth: 5,
        widthUnits: 'pixels',
        capRounded: true,
        billboard: true,
        // Drawn over the voxels so the route stays visible where it flies through rough air.
        parameters: { depthCompare: 'always' },
        updateTriggers: { getPath: exaggeration.value }
      })
    )
  }

  if (live.value?.altitudeFt !== undefined) {
    const p = live.value
    layers.push(
      new ScatterplotLayer({
        id: 'live-aircraft',
        data: [p],
        getPosition: (d: AircraftView) => [d.lon, d.lat, z(d.altitudeFt ?? 0)],
        getRadius: 8,
        radiusUnits: 'pixels',
        getFillColor: [...blue, 255],
        getLineColor: [255, 255, 255, 255],
        lineWidthMinPixels: 2,
        stroked: true,
        billboard: true,
        parameters: { depthCompare: 'always' },
        updateTriggers: { getPosition: exaggeration.value }
      })
    )
  }

  if (column.value) {
    const col = column.value
    layers.push(
      new LineLayer({
        id: 'column',
        data: [col],
        getSourcePosition: (d: TurbulenceColumn) => [d.lon, d.lat, 0],
        getTargetPosition: (d: TurbulenceColumn) => [d.lon, d.lat, z(45000)],
        getColor: [...blue, 220],
        getWidth: 2,
        updateTriggers: { getTargetPosition: exaggeration.value }
      })
    )
  }
  return layers
}

function render() {
  deck?.setProps({ layers: buildLayers() })
}

// Deck's onClick didn't fire in Chromium with deck.gl 9.4 (its click recognizer waits on the double-click-drag one),
// so clicks are picked by hand, ignoring clicks that end a drag.
let downAt: [number, number] = [0, 0]
function onPointerDown(e: PointerEvent) {
  downAt = [e.offsetX, e.offsetY]
}

function onCanvasClick(e: MouseEvent) {
  if (!deck || Math.hypot(e.offsetX - downAt[0], e.offsetY - downAt[1]) > 4) return
  const info = deck.pickObject({ x: e.offsetX, y: e.offsetY, radius: 2, layerIds: ['voxels'] })
  const v = info?.object as Voxel | undefined
  const ground = deck.getViewports()[0]?.unproject([e.offsetX, e.offsetY])
  const [lon, lat] = v ? [v.lon, v.lat] : (ground ?? [])
  showColumn(lat, lon)
}

async function showColumn(lat?: number, lon?: number) {
  const frame = selectedFrame.value
  if (!frame) return
  if (lat === undefined || lon === undefined) return
  try {
    column.value = await fetchColumn(frame.id, lat, lon)
  } catch (e) {
    // Usually a newer nowcast replaced this frame: reload the frames, then retry on the new one.
    console.warn('column fetch failed', e)
    status.value = await fetchStatus().catch(() => status.value)
    const latest = selectedFrame.value
    if (latest && latest.id !== frame.id)
      column.value = await fetchColumn(latest.id, lat, lon).catch(() => undefined)
  }
}

function tooltip(info: PickingInfo) {
  const v = info.object as Voxel | undefined
  if (!v) return null
  return {
    html: `<b>${flightLevel(v.levelFt)}</b> · ${categoryLabels[v.category]} (EDR ${v.edr.toFixed(2)})`,
    style: {
      background: 'rgba(24, 39, 52, 0.85)', // --my-blue-transparent, a bit more opaque to read over voxels
      color: 'rgba(255, 255, 255, 0.95)',
      borderRadius: '8px',
      padding: '6px 10px',
      fontSize: '12px',
      boxShadow: '0px 1px 3px #000000'
    }
  }
}

async function loadVolume() {
  const frame = selectedFrame.value
  if (!frame) return
  loadingVolume.value = true
  try {
    const v = await fetchVolume(frame.id)
    if (selectedFrame.value?.id !== frame.id) return
    volume.value = v
    voxels.value = toVoxels(v)
    if (column.value) column.value = await fetchColumn(frame.id, column.value.lat, column.value.lon)
  } catch (e) {
    console.warn('volume fetch failed', e)
  } finally {
    loadingVolume.value = false
  }
}

function flyTo(south: number, west: number, north: number, east: number) {
  if (!deck || !mapEl.value) return
  const { clientWidth: width, clientHeight: height } = mapEl.value
  const { longitude, latitude, zoom } = new WebMercatorViewport({ width, height }).fitBounds(
    [
      [west, south],
      [east, north]
    ],
    { padding: Math.min(width, height) * 0.2 }
  )
  deck.setProps({
    initialViewState: {
      longitude,
      latitude,
      zoom: zoom - 0.3,
      pitch: 55,
      bearing: -15,
      maxPitch: 85
    }
  })
}

async function loadFlight() {
  const q = flightQuery.value.trim()
  if (!q) return
  error.value = undefined
  loadingFlight.value = true
  try {
    const lookup = await lookupFlight(q)
    live.value = lookup.live
    if (!lookup.route) {
      analysis.value = undefined
      flightLabel.value = ''
      error.value = lookup.live
        ? `${lookup.callsign} is flying, but its route isn't in the public database: showing its position only.`
        : `No route found for ${q}.`
      if (lookup.live) {
        const l = lookup.live
        flyTo(l.lat - 3, l.lon - 4, l.lat + 3, l.lon + 4)
        if (l.altitudeFt) levelFt.value = Math.round(l.altitudeFt / 1000) * 1000
      }
      return
    }
    const r = lookup.route
    analysis.value = await analyzeFlight({ origin: r.origin, destination: r.destination })
    flightLabel.value = `${r.iataCallsign ?? r.callsign}: ${r.origin.code} → ${r.destination.code}, cruise ${flightLevel(
      analysis.value.cruiseAltitudeFt
    )}`
    levelFt.value =
      Math.round((lookup.live?.altitudeFt ?? analysis.value.cruiseAltitudeFt) / 1000) * 1000
    const lats = analysis.value.points.map((p) => p.lat)
    const lons = analysis.value.points.map((p) => p.lon)
    flyTo(Math.min(...lats), Math.min(...lons), Math.max(...lats), Math.max(...lons))
    router.replace({ query: { q } })
  } catch (e) {
    error.value = `Couldn't load ${q}: ${e}`
  } finally {
    loadingFlight.value = false
  }
}

watch(selectedFrame, (f, old) => {
  if (f?.id !== old?.id) loadVolume()
})
watch([visibleVoxels, analysis, live, column, levelFt, exaggeration, status], render)

let statusTimer: number | undefined

onMounted(async () => {
  deck = new Deck({
    parent: mapEl.value,
    initialViewState: {
      longitude: -97,
      latitude: 36.5,
      zoom: 3.7,
      pitch: 55,
      bearing: -15,
      maxPitch: 85
    },
    controller: true,
    layers: buildLayers(),
    getTooltip: tooltip
  })
  mapEl.value!.addEventListener('pointerdown', onPointerDown)
  mapEl.value!.addEventListener('click', onCanvasClick)
  try {
    status.value = await fetchStatus()
  } catch (e) {
    error.value = `Turbulence data unavailable: ${e}`
  }
  statusTimer = window.setInterval(async () => {
    try {
      status.value = await fetchStatus()
    } catch {
      // keep the last status
    }
  }, 120000)
  const q = route.query.q
  if (typeof q === 'string' && q) {
    flightQuery.value = q
    await loadFlight()
  }
})

onBeforeUnmount(() => {
  window.clearInterval(statusTimer)
  deck?.finalize()
  deck = undefined
})
</script>

<style scoped>
.map-page {
  position: relative;
  width: 100%;
  height: calc(100vh - 80px);
  border-radius: 8px;
  overflow: hidden;
  background: #e5e7eb;
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
.hint {
  color: rgba(255, 255, 255, 0.6);
  font-size: 11px;
}
.top-left {
  top: 12px;
  left: 56px;
  width: 330px;
}
.light-switch :deep(.v-label) {
  font-size: 13px;
}
.flight-field {
  flex: 1;
}
.top-right {
  top: 12px;
  right: 12px;
  width: 250px;
}
.right-profile {
  top: 330px;
  right: 12px;
  width: 250px;
}
.close {
  position: absolute;
  top: 4px;
  right: 4px;
}
.bottom-left {
  position: absolute;
  z-index: 1000;
  bottom: 24px;
  left: 12px;
}
</style>
