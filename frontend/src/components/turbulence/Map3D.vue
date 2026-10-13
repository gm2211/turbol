<template>
  <div class="map3d">
    <div ref="canvasEl" class="canvas" @contextmenu.prevent />

    <div v-if="route" class="route-chip glass-panel" :class="{ mobile }">
      <v-icon icon="mdi-airplane" size="16" class="mr-1" />
      <div class="route-text">
        <div class="route-label">{{ route.label }}</div>
        <div v-if="route.path" class="muted route-path">{{ route.path }}</div>
        <div v-if="!mobile" class="muted route-path">
          <template v-if="corridorShown">
            The air around the flight as it passes, felt by
            {{ route.aircraftLabel ?? 'its aircraft' }} · bright blocks are the ones it flies
            through
          </template>
          <template v-else>
            Line colored by the blocks it flies through{{ frameTimeLabel }} · hover it for the
            forecast when the flight passes
          </template>
        </div>
      </div>
      <v-btn
        icon="mdi-close"
        size="x-small"
        variant="text"
        theme="dark"
        aria-label="Hide the route"
        @click="emit('clearRoute')"
      />
    </div>

    <div v-if="loadState" class="loading glass-panel">
      <div>{{ loadState }}</div>
      <v-progress-linear
        :model-value="loadProgress"
        :indeterminate="loadProgress === undefined"
        color="var(--my-accent)"
        height="3"
        class="mt-1"
      />
    </div>

    <div v-if="corridorShown && !mobile" class="projections glass-panel">
      <CorridorProjections
        :points="route!.points"
        :corridor="corridor!"
        :factor="factor"
        :thresholds="mediumThresholds"
        :what-if="whatIf"
        :hover-minute="hoverMinute"
        dark
        @hover="hoverMinute = $event"
        @update:what-if="whatIf = $event"
      />
    </div>

    <v-btn
      v-if="mobile && !showPanel"
      icon="mdi-cube-outline"
      theme="dark"
      class="glass-panel panel-fab"
      aria-label="3D settings"
      @click="showPanel = true"
    />
    <!--
      On desktop the panel joins the live map's bottom-left column, above the legend, so they never overlap; on
      phones the sheet sits on the page itself, above the legend and the 2D/3D switch.
    -->
    <Teleport :to="mobile ? '#map-page' : '#map3d-panel-slot'" defer>
      <div
        v-if="!mobile || showPanel"
        class="panel glass-panel controls3d"
        :class="{ sheet: mobile }"
      >
        <div class="d-flex align-center justify-space-between">
          <span class="panel-title">3D</span>
          <span class="muted fps" v-if="fps">{{ fps }} fps</span>
          <v-btn
            v-if="mobile"
            icon="mdi-close"
            size="small"
            variant="text"
            theme="dark"
            aria-label="Close"
            @click="showPanel = false"
          />
        </div>
        <div class="muted" v-if="loadState">{{ loadState }}</div>
        <div class="muted" v-else-if="corridorShown">
          {{ corridorCells.length.toLocaleString() }} rough blocks around the flight
        </div>
        <div class="muted" v-else-if="storeVersion">
          {{ visible.count.toLocaleString() }} turbulence columns shown
        </div>
        <div class="muted">Pick the levels on the flight-level rail</div>
        <v-switch
          v-if="route"
          v-model="aroundFlight"
          :label="
            corridorFailed ? 'Around the flight (unavailable)' : 'Only the air around the flight'
          "
          :disabled="corridorFailed"
          density="compact"
          hide-details
          theme="dark"
          color="var(--my-accent)"
        />
        <CorridorProjections
          v-if="corridorShown && mobile"
          class="my-1"
          :points="route!.points"
          :corridor="corridor!"
          :factor="factor"
          :thresholds="mediumThresholds"
          :what-if="whatIf"
          :hover-minute="hoverMinute"
          dark
          @hover="hoverMinute = $event"
          @update:what-if="whatIf = $event"
        />
        <v-switch
          v-model="showLight"
          label="Show light turbulence"
          density="compact"
          hide-details
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
        <div class="hint" v-if="mobile">
          Two fingers: slide up or down to tilt, twist to rotate · tap a plane for details
        </div>
        <div class="hint" v-else>
          Shift+drag or right-drag to tilt and rotate · click a plane for details
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { deckCursor } from '@/components/turbulence/mapCursor'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, toRaw, watch } from 'vue'
import {
  Deck,
  type Layer,
  LinearInterpolator,
  type MapViewState,
  type PickingInfo,
  WebMercatorViewport
} from '@deck.gl/core'
import { IconLayer, LineLayer, PathLayer, ScatterplotLayer } from '@deck.gl/layers'
import { SimpleMeshLayer } from '@deck.gl/mesh-layers'
import { CubeGeometry } from '@luma.gl/engine'
import {
  type AircraftView,
  type FrameInfo,
  type TurbulenceStatus,
  categoryColors,
  categoryLabels,
  flightLevel,
  overlayRgba,
  prefetchVolume
} from '@/api/turbulence'
import { basemapLayer, blue, ftToM, hexRgb, tooltipStyle } from '@/components/turbulence/scene3d'
import {
  type ColumnBatch,
  type VolumeMessage,
  type VolumeRequest,
  columnCategories
} from '@/components/turbulence/volumeTypes'
import { planeFill, planePath, ringColor } from '@/components/turbulence/planeIcon'
import { decodeVolume } from '@/components/turbulence/volumeCodec'
import { VolumeProfiles, levelNear } from '@/components/turbulence/volumeProfile'
import { useMobile } from '@/util/useMobile'
import { BlockIndex, type RouteStep, routeSteps } from '@/components/turbulence/routeBlocks'
import CorridorProjections from '@/components/turbulence/CorridorProjections.vue'
import {
  type Corridor,
  type WhatIf,
  type WhatIfPoint,
  corridorCategory,
  fetchCorridor,
  noChange,
  whatIfPath
} from '@/components/turbulence/corridor'
import { pointAt } from '@/api/flights'
import { destination as destinationOf } from '@/fly/geo'
import type { Route3D, RoutePoint } from '@/api/flights'
// Inlined into this chunk: a separate worker file was one more round trip before anything could draw.
import VolumeWorker from '@/components/turbulence/volumeWorker?worker&inline'

/** A map view in Leaflet terms: zoom counts 256 px tiles. */
export interface MapView {
  lat: number
  lon: number
  zoom: number
  pitch: number
  bearing: number
}

const props = defineProps<{
  view: MapView
  aircraft: AircraftView[]
  frame?: FrameInfo
  status?: TurbulenceStatus
  levelFt: number
  /** Lowest and highest flight level shown, in feet. */
  band: [number, number]
  /** A route analyzed on the Analyze page, drawn at its altitudes and colored by its forecast. */
  route?: Route3D
}>()
const emit = defineEmits<{
  /** The camera settled somewhere new. */
  move: [view: MapView]
  /** A plane was clicked. */
  select: [hex: string]
  /** The route's close button was clicked. */
  clearRoute: []
}>()

const canvasEl = ref<HTMLDivElement>()
/**
 * Every level's columns for the loaded frame, as index-aligned typed arrays filled batch by batch by the
 * volume worker (nearest to the camera first). storeVersion bumps on each batch so the scene redraws.
 */
let store: ColumnBatch = emptyBatch(0)
let storeLength = 0
const storeVersion = ref(0)
const loadState = ref('')
const loadProgress = ref<number>()
const mobile = useMobile()
const showPanel = ref(false)
const showLight = ref(true)
const exaggeration = ref(30)
const fps = ref(0)

/**
 * Height in deck.gl metres for an altitude at a latitude. Web Mercator draws heights 1/cos(lat) taller, as it does
 * widths (twice as tall at 60°N as at the equator), so this undoes that: one altitude stands equally high everywhere.
 */
const z = (ft: number, lat: number) =>
  ft * ftToM * exaggeration.value * Math.cos((lat * Math.PI) / 180)
const maxPitch = 85

function emptyBatch(n: number): ColumnBatch {
  return {
    lat: new Float32Array(n),
    lon: new Float32Array(n),
    bottomFt: new Float32Array(n),
    topFt: new Float32Array(n),
    levelFt: new Float32Array(n),
    topLevelFt: new Float32Array(n),
    edr: new Float32Array(n),
    category: new Uint8Array(n),
    sizeKm: new Float32Array(n)
  }
}

function append(batch: ColumnBatch) {
  const n = batch.lat.length
  if (storeLength + n > store.lat.length) {
    const grown = emptyBatch(Math.max(store.lat.length * 2, storeLength + n))
    for (const k of Object.keys(store) as (keyof ColumnBatch)[])
      grown[k].set(store[k].subarray(0, storeLength) as never)
    store = grown
  }
  for (const k of Object.keys(store) as (keyof ColumnBatch)[])
    store[k].set(batch[k] as never, storeLength)
  storeLength += n
  storeVersion.value++
}

const lightIndex = columnCategories.indexOf('Light')

/** The columns inside the rail's band (cut to it), as indexes into the store plus their cut extents. */
const visible = computed(() => {
  void storeVersion.value
  const [lo, hi] = props.band
  const halfGap = 500
  const index = new Uint32Array(storeLength)
  const bottom = new Float32Array(storeLength)
  const top = new Float32Array(storeLength)
  let count = 0
  for (let i = 0; i < storeLength; i++) {
    if (store.topLevelFt[i]! < lo || store.levelFt[i]! > hi) continue
    if (!showLight.value && store.category[i] === lightIndex) continue
    index[count] = i
    bottom[count] = Math.max(store.bottomFt[i]!, lo - halfGap)
    top[count] = Math.min(store.topFt[i]!, hi + halfGap)
    count++
  }
  return { count, index, bottom, top }
})

// The same neutral plane as the 2D map: slate fill, white outline.
const planeSvg =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24"><g transform="translate(1.2 1.2) scale(.9)"><path fill="${planeFill}" stroke="#fff" stroke-width="1.4" d="${planePath}"/></g></svg>`
  )
const planeIconDef = { url: planeSvg, width: 48, height: 48, mask: false }

let deck: Deck | undefined
let viewState: MapViewState
const cube = new CubeGeometry()

// deck.gl's world is 512 px wide at zoom 0, Leaflet's 256 px.
const toDeck = (v: MapView): MapViewState => ({
  latitude: v.lat,
  longitude: v.lon,
  zoom: v.zoom - 1,
  pitch: v.pitch,
  bearing: v.bearing,
  maxPitch,
  // The 2D map's limit (the basemap's last level).
  maxZoom: 11
})
const fromDeck = (v: MapViewState): MapView => ({
  lat: v.latitude,
  lon: v.longitude,
  zoom: v.zoom + 1,
  pitch: v.pitch ?? 0,
  bearing: v.bearing ?? 0
})

const stalkRgba: [number, number, number, number] = [...hexRgb(planeFill), 120]

function buildLayers(): Layer[] {
  const layers: Layer[] = [basemapLayer()]
  const c = props.status?.coverage
  if (corridorShown.value) {
    layers.push(...corridorLayers(), ...routeLayers())
    return [...layers, ...aircraftLayers()]
  }
  if (c) {
    // GTG's 3 km edge at the map's level; outside it the blocks are the coarse global layer's.
    const outline = props.status?.gtgOutline
    const ring = outline?.length
      ? outline.map(([lat, lon]) => [lon, lat, z(props.levelFt, lat)])
      : [
          [c.west, c.south, z(props.levelFt, c.south)],
          [c.east, c.south, z(props.levelFt, c.south)],
          [c.east, c.north, z(props.levelFt, c.north)],
          [c.west, c.north, z(props.levelFt, c.north)],
          [c.west, c.south, z(props.levelFt, c.south)]
        ]
    layers.push(
      new PathLayer({
        id: 'level-outline',
        data: [ring],
        getPath: (d) => d,
        getColor: [...blue, 160],
        getWidth: 1.5,
        widthUnits: 'pixels'
      })
    )
  }
  if (storeLength) {
    const { index, bottom, top } = visible.value
    const colors = columnCategories.map((c) => overlayRgba[c])
    const triggers = [exaggeration.value, visible.value]
    layers.push(
      // Index-based accessors over the typed arrays: no object per column.
      new SimpleMeshLayer({
        id: 'voxels',
        data: columnData.value,
        mesh: cube,
        getPosition: (_: unknown, { index: j }: { index: number }) => [
          store.lon[index[j]!]!,
          store.lat[index[j]!]!,
          z((bottom[j]! + top[j]!) / 2, store.lat[index[j]!]!)
        ],
        // Half the block width (km to m, halved) with a small gap between blocks.
        getScale: (_: unknown, { index: j }: { index: number }) => [
          store.sizeKm[index[j]!]! * 460,
          store.sizeKm[index[j]!]! * 460,
          z((top[j]! - bottom[j]!) / 2, store.lat[index[j]!]!)
        ],
        getColor: (_: unknown, { index: j }: { index: number }) =>
          colors[store.category[index[j]!]!]!,
        material: { ambient: 0.6, diffuse: 0.5, shininess: 8, specularColor: [40, 40, 40] },
        pickable: true,
        updateTriggers: { getPosition: triggers, getScale: triggers, getColor: visible.value }
      })
    )
  }
  layers.push(...routeLayers())
  return [...layers, ...aircraftLayers()]
}

function aircraftLayers(): Layer[] {
  const flying = props.aircraft.filter((a) => a.altitudeFt !== undefined)
  const ringed = flying.filter((a) => ringColor(a.category))
  return [
    // A stalk from the ground to each plane shows its altitude.
    new LineLayer<AircraftView>({
      id: 'aircraft-stalks',
      data: flying,
      getSourcePosition: (a) => [a.lon, a.lat, 0],
      getTargetPosition: (a) => [a.lon, a.lat, z(a.onGround ? 0 : (a.altitudeFt ?? 0), a.lat)],
      getColor: stalkRgba,
      getWidth: 1,
      widthUnits: 'pixels',
      updateTriggers: { getTargetPosition: exaggeration.value }
    }),
    // The turbulence a plane is in shows as a ring around it, as on the 2D map, only for Light or worse.
    new ScatterplotLayer<AircraftView>({
      id: 'aircraft-rings',
      data: ringed,
      getPosition: (a) => [a.lon, a.lat, z(a.onGround ? 0 : (a.altitudeFt ?? 0), a.lat)],
      getRadius: 13,
      radiusUnits: 'pixels',
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 2,
      getLineColor: (a) => [...hexRgb(ringColor(a.category)!), 255],
      getFillColor: (a) => [...hexRgb(ringColor(a.category)!), 56],
      parameters: { depthCompare: 'always' },
      updateTriggers: { getPosition: exaggeration.value }
    }),
    new IconLayer<AircraftView>({
      id: 'aircraft',
      data: flying,
      getIcon: () => planeIconDef,
      getPosition: (a) => [a.lon, a.lat, z(a.onGround ? 0 : (a.altitudeFt ?? 0), a.lat)],
      // Lying flat at its altitude, nose along its track (deck angles turn counter-clockwise).
      billboard: false,
      getAngle: (a) => -(a.trackDeg ?? 0),
      getSize: 20,
      sizeUnits: 'pixels',
      pickable: true,
      // Kept in front of the turbulence blocks so planes flying through rough air stay visible.
      parameters: { depthCompare: 'always' },
      updateTriggers: { getPosition: exaggeration.value }
    })
  ]
}

/**
 * The turbulence around the route (see corridor.ts): levels above and below and either side of the track, sampled
 * when the flight passes and classified for its aircraft, the same way as the route itself. Shown instead of the
 * frame's blocks while a route is on, so the line and the blocks around it always agree.
 */
const corridor = shallowRef<Corridor>()
const corridorFailed = ref(false)
const aroundFlight = ref(true)
const whatIf = ref<WhatIf>(props.route?.whatIf ?? noChange)
/** Minutes after takeoff being read, shared by the 3D route and the side and top views. */
const hoverMinute = ref<number>()
const corridorShown = computed(
  () =>
    aroundFlight.value &&
    !!props.route &&
    !!corridor.value &&
    corridor.value.points >= props.route.points.length
)
const factor = computed(() => props.route?.aircraftFactor ?? 1)
const mediumThresholds = computed(
  () =>
    props.status?.thresholds.Medium ?? { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 }
)
const whatIfPoints = computed<WhatIfPoint[]>(() =>
  corridor.value && props.route ? whatIfPath(corridor.value, props.route.points, whatIf.value) : []
)

let corridorFor: Route3D | undefined
async function loadCorridor() {
  const r = props.route
  if (r === corridorFor) return
  corridorFor = r
  corridor.value = undefined
  corridorFailed.value = false
  whatIf.value = r?.whatIf ?? noChange
  if (!r || r.points.length < 2) return
  try {
    const c = await fetchCorridor(r.points)
    if (corridorFor === r) corridor.value = c
  } catch (e) {
    console.warn('corridor load failed', e)
    if (corridorFor === r) corridorFailed.value = true
  }
}

/** Category index of the corridor cell the (what-if) flight is in at point k, as felt by its aircraft. */
const cellCategory = (k: number, l: number, o: number) =>
  corridorCategory(corridor.value!, k, l, o, factor.value, mediumThresholds.value)

/**
 * The what-if flight as half-steps: each point's cell reaches halfway to its neighbours, so the first half of a
 * step takes the color of the cell it starts in and the second half the one it ends in.
 */
function corridorSegments(): RouteSegment[] {
  const p = whatIfPoints.value
  const out: RouteSegment[] = []
  for (let k = 0; k + 1 < p.length; k++) {
    const a: [number, number, number] = [p[k]!.lon, p[k]!.lat, p[k]!.altitudeFt]
    const b: [number, number, number] = [p[k + 1]!.lon, p[k + 1]!.lat, p[k + 1]!.altitudeFt]
    const mid: [number, number, number] = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2]
    out.push({ from: a, to: mid, point: k, block: cellCategory(k, p[k]!.level, p[k]!.offset) })
    out.push({
      from: mid,
      to: b,
      point: k + 1,
      block: cellCategory(k + 1, p[k + 1]!.level, p[k + 1]!.offset)
    })
  }
  return out
}

interface CorridorCell {
  position: [number, number, number]
  /** Half-sizes in metres: along the track, across it, and up (in feet, lifted at draw time). */
  along: number
  across: number
  /** Yaw in degrees, counter-clockwise from east, as deck.gl turns meshes. */
  yaw: number
  category: number
  /** The (what-if) flight flies through it. */
  onPath: boolean
  k: number
  level: number
  offset: number
}

/**
 * Every Light-or-worse corridor cell as a box: as long as the stretch between the midpoints to its neighbours, one
 * offset step wide and one level tall, turned to the track. Smooth air isn't drawn, as in the frame's blocks.
 */
const corridorCells = computed<CorridorCell[]>(() => {
  const c = corridor.value
  const points = props.route?.points ?? []
  if (!c || !corridorShown.value) return []
  const path = whatIfPoints.value
  const nl = c.levelsFt.length
  const no = c.offsetsKm.length
  const acrossM = (c.offsetsKm[1]! - c.offsetsKm[0]!) * 500 * 0.92
  // Where each offset column is at each point.
  const where = whatIfPath(c, points, noChange).map((wp, k) =>
    c.offsetsKm.map((km) =>
      km === 0
        ? [points[k]!.lat, points[k]!.lon]
        : (destinationOf(points[k]!.lat, points[k]!.lon, wp.trackDeg + 90, km) as [number, number])
    )
  )
  const cells: CorridorCell[] = []
  for (let k = 0; k < points.length; k++) {
    const prev = points[Math.max(k - 1, 0)]!
    const next = points[Math.min(k + 1, points.length - 1)]!
    const alongM = ((next.distanceKm - prev.distanceKm) / 2) * 500 * 0.96
    const track = path[k]!.trackDeg
    for (let l = 0; l < nl; l++) {
      const ft = points[k]!.altitudeFt + c.levelsFt[l]!
      if (ft < 0) continue
      for (let o = 0; o < no; o++) {
        const category = cellCategory(k, l, o)
        if (category < 1) continue
        const [lat, lon] = where[k]![o]!
        cells.push({
          position: [lon!, lat!, ft],
          along: alongM,
          across: acrossM,
          yaw: 90 - track,
          category,
          onPath: path[k]!.level === l && path[k]!.offset === o,
          k,
          level: l,
          offset: o
        })
      }
    }
  }
  return cells
})

function corridorLayers(): Layer[] {
  const colors = columnCategories.map((c) => hexRgb(categoryColors[c]))
  const half = (corridor.value!.levelsFt[1]! - corridor.value!.levelsFt[0]!) / 2
  const triggers = [exaggeration.value, corridorCells.value]
  const layers: Layer[] = [
    new SimpleMeshLayer<CorridorCell>({
      id: 'corridor',
      data: corridorCells.value,
      mesh: cube,
      getPosition: (d) => [d.position[0], d.position[1], z(d.position[2], d.position[1])],
      // The flight's own cells a little larger, so they show through the faint ones around them.
      getScale: (d) => {
        const grow = d.onPath ? 1.1 : 1
        return [d.along * grow, d.across * grow, z(half * grow, d.position[1])]
      },
      getOrientation: (d) => [0, d.yaw, 0],
      // The cells the flight flies through stand out; the rest of the corridor is context.
      getColor: (d) => [...colors[d.category]!, d.onPath ? 255 : 34],
      material: { ambient: 0.6, diffuse: 0.5, shininess: 8, specularColor: [40, 40, 40] },
      pickable: true,
      updateTriggers: { getPosition: triggers, getScale: triggers, getColor: triggers }
    })
  ]
  if (whatIf.value.shiftFt || whatIf.value.offsetKm) {
    // The planned flight, faint, under the one being tried.
    const pts = props.route!.points
    layers.push(
      new PathLayer<RoutePoint[]>({
        id: 'route-planned',
        data: [pts],
        getPath: (d) =>
          d.map((p): [number, number, number] => [p.lon, p.lat, z(p.altitudeFt, p.lat)]),
        getColor: [16, 50, 79, 140],
        getWidth: 2,
        widthUnits: 'pixels',
        parameters: { depthCompare: 'always' },
        updateTriggers: { getPath: triggers }
      })
    )
  }
  const at = hoverMinute.value === undefined ? undefined : hoverPosition(hoverMinute.value)
  if (at)
    layers.push(
      new ScatterplotLayer<[number, number, number]>({
        id: 'route-cursor',
        data: [at],
        getPosition: (d) => [d[0], d[1], z(d[2], d[1])],
        getRadius: 9,
        radiusUnits: 'pixels',
        stroked: true,
        lineWidthUnits: 'pixels',
        getLineWidth: 3,
        getLineColor: [255, 255, 255, 255],
        getFillColor: [16, 50, 79, 255],
        parameters: { depthCompare: 'always' },
        updateTriggers: { getPosition: [at, exaggeration.value] }
      })
    )
  return layers
}

/** Where the (what-if) flight is `minute` minutes after takeoff. */
function hoverPosition(minute: number): [number, number, number] | undefined {
  const points = props.route?.points ?? []
  const p = pointAt(points, minute)
  const wp = p && whatIfPoints.value[points.indexOf(p)]
  return wp && [wp.lon, wp.lat, wp.altitudeFt]
}

/** A step of the route and the category of the block it flies through (-1: none, smooth air). */
interface RouteSegment extends RouteStep {
  block: number
}

/** The drawn blocks by place, for coloring the route; rebuilt as blocks stream in. */
const blockIndex = computed(() => {
  void storeVersion.value
  return props.route && storeLength ? new BlockIndex(store, storeLength) : undefined
})

/**
 * The route in steps of at most 3 km (a sixth of a GTG block), each with the category of the block it's in, so the
 * line changes color where it enters or leaves a block. Until blocks load, each step takes its route point's forecast.
 */
const routeSegments = computed<RouteSegment[]>(() => {
  const points = props.route?.points ?? []
  if (points.length < 2) return []
  if (corridorShown.value) return corridorSegments()
  const index = blockIndex.value
  return routeSteps(points, 3).map((s) => ({
    ...s,
    block: index
      ? index.categoryAt(
          (s.from[1] + s.to[1]) / 2,
          (s.from[0] + s.to[0]) / 2,
          (s.from[2] + s.to[2]) / 2
        )
      : columnCategories.indexOf(points[s.point]!.category as (typeof columnCategories)[number])
  }))
})

const blockRgb = (block: number): [number, number, number] =>
  hexRgb(categoryColors[columnCategories[block] ?? 'Smooth'])

/** " at 21:00", the drawn forecast hour, local. */
const frameTimeLabel = computed(() =>
  props.frame
    ? ` at ${new Date(props.frame.validTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`
    : ''
)

/**
 * The analyzed route as a line at its altitudes, each stretch in the color of the block it flies through (so what
 * the line says is what the blocks around it show), over a faint curtain down to the ground so its height reads at
 * any angle. Its tooltip gives the forecast when the flight passes, which can differ (see BlockIndex).
 */
function routeLayers(): Layer[] {
  const points = props.route?.points ?? []
  if (points.length < 2) return []
  const at = (p: RoutePoint): [number, number, number] => [p.lon, p.lat, z(p.altitudeFt, p.lat)]
  const lift = ([lon, lat, ft]: [number, number, number]): [number, number, number] => [
    lon,
    lat,
    z(ft, lat)
  ]
  const segments = routeSegments.value
  const every = Math.max(1, Math.round(segments.length / 120))
  const curtain = segments.filter((_, i) => i % every === 0)
  const triggers = [exaggeration.value, segments]
  return [
    new LineLayer<RouteSegment>({
      id: 'route-curtain',
      data: curtain,
      getSourcePosition: (s) => [s.from[0], s.from[1], 0],
      getTargetPosition: (s) => lift(s.from),
      getColor: (s) => [...blockRgb(s.block), 70],
      getWidth: 1,
      widthUnits: 'pixels',
      updateTriggers: { getTargetPosition: triggers, getColor: triggers }
    }),
    new PathLayer<RouteSegment>({
      id: 'route',
      data: segments,
      getPath: (s) => [lift(s.from), lift(s.to)],
      getColor: (s) => [...blockRgb(s.block), 255],
      getWidth: 6,
      widthUnits: 'pixels',
      capRounded: true,
      pickable: true,
      // In front of the blocks it flies through, like the planes.
      parameters: { depthCompare: 'always' },
      updateTriggers: { getPath: triggers, getColor: triggers }
    }),
    new ScatterplotLayer<RoutePoint>({
      id: 'route-ends',
      data: [points[0]!, points[points.length - 1]!],
      getPosition: at,
      getRadius: 6,
      radiusUnits: 'pixels',
      getFillColor: [16, 50, 79, 255],
      stroked: true,
      getLineColor: [255, 255, 255, 255],
      lineWidthUnits: 'pixels',
      getLineWidth: 2,
      parameters: { depthCompare: 'always' },
      updateTriggers: { getPosition: triggers }
    })
  ]
}

function routeTooltip(s: RouteSegment) {
  const p = props.route!.points[s.point]!
  const time = new Date(p.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  const h = Math.floor(p.minutesFromStart / 60)
  const m = Math.round(p.minutesFromStart % 60)
  const edr = p.edr != null ? ` (EDR ${p.edr.toFixed(2)})` : ''
  const shown = columnCategories[s.block] ?? 'Smooth'
  const blocks = blockIndex.value
    ? `<br/>Blocks${frameTimeLabel.value}: <b style="color:${categoryColors[shown]}">${categoryLabels[shown]}</b>`
    : ''
  return {
    html:
      `<b>${flightLevel(p.altitudeFt)}</b> · ${time} (${h}h${String(m).padStart(2, '0')} after takeoff)<br/>` +
      `When it passes: <b style="color:${categoryColors[p.category]}">${categoryLabels[p.category]}</b>${edr} · ` +
      `${p.source}${blocks}`,
    style: tooltipStyle
  }
}

function corridorTooltip(d: CorridorCell) {
  const c = corridor.value!
  const p = props.route!.points[d.k]!
  const time = new Date(p.time).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  const shift = c.levelsFt[d.level]!
  const km = c.offsetsKm[d.offset]!
  const where = [
    shift ? `${Math.abs(shift).toLocaleString()} ft ${shift > 0 ? 'above' : 'below'} the plan` : '',
    km ? `${Math.abs(km)} km ${km < 0 ? 'left' : 'right'} of the track` : ''
  ]
    .filter(Boolean)
    .join(', ')
  const cat = columnCategories[d.category]!
  return {
    html:
      `<b>${flightLevel(p.altitudeFt + shift)}</b> · ${time}${where ? ` · ${where}` : ' · on the planned path'}<br/>` +
      `<b style="color:${categoryColors[cat]}">${categoryLabels[cat]}</b> ` +
      `(EDR ${(corridorEdrOf(d) * factor.value).toFixed(2)} felt)` +
      (d.onPath ? '<br/>The flight flies through this' : ''),
    style: tooltipStyle
  }
}
const corridorEdrOf = (d: CorridorCell) => {
  const c = corridor.value!
  return c.edr[(d.k * c.levelsFt.length + d.level) * c.offsetsKm.length + d.offset]! / 200
}

function render() {
  deck?.setProps({ layers: buildLayers() })
}

function tooltip(info: PickingInfo) {
  if (info.layer?.id === 'route' && info.object) return routeTooltip(info.object as RouteSegment)
  if (info.layer?.id === 'corridor' && info.object)
    return corridorTooltip(info.object as CorridorCell)
  if (info.layer?.id === 'aircraft') {
    const a = info.object as AircraftView
    const edr = a.edr != null ? ` (EDR ${a.edr.toFixed(2)})` : ''
    return {
      html:
        `<b>${a.callsign ?? a.hex}</b> ${a.typeCode ?? ''}<br/>${flightLevel(a.altitudeFt)} · ` +
        `${Math.round(a.groundSpeedKts ?? 0)} kt<br/>Turbulence now: ` +
        `<b style="color:${categoryColors[a.category]}">${categoryLabels[a.category]}</b>${edr}`,
      style: tooltipStyle
    }
  }
  if (info.layer?.id !== 'voxels' || info.index < 0) return null
  const { index } = visible.value
  const i = index[info.index]
  if (i === undefined) return null
  const [lo, hi] = props.band
  const bottomLevel = Math.max(store.levelFt[i]!, lo)
  const topLevel = Math.min(store.topLevelFt[i]!, hi)
  const stacked = topLevel !== bottomLevel
  const category = categoryLabels[columnCategories[store.category[i]!]!]
  if (!stacked)
    return {
      html: `<b>${flightLevel(bottomLevel)}</b> · ${category} (EDR ${store.edr[i]!.toFixed(2)})`,
      style: tooltipStyle
    }
  const range = `${flightLevel(bottomLevel)}–${flightLevel(topLevel)}`
  const hovered = levelUnderPointer(info, i, bottomLevel, topLevel)
  return {
    html: hovered
      ? `<b>${flightLevel(hovered.levelFt)}</b> · ${category} (EDR ${hovered.edr.toFixed(2)})<br/>` +
        `<span style="opacity:.7">${category} ${range}, worst EDR ${store.edr[i]!.toFixed(2)}</span>`
      : `<b>${range}</b> · ${category} (worst EDR ${store.edr[i]!.toFixed(2)})`,
    style: tooltipStyle
  }
}

/**
 * The level of stacked column `i` under the pointer, and its own EDR: the pointer's altitude comes from the depth
 * buffer, the level's EDR from the frame's volume (each level is 1,000 ft in the nowcast).
 */
function levelUnderPointer(info: PickingInfo, i: number, lo: number, hi: number) {
  const picked = deck?.pickObject({ x: info.x, y: info.y, layerIds: ['voxels'], unproject3D: true })
  const altitudeM = picked?.coordinate?.[2]
  if (altitudeM === undefined || !loadedVolume) return undefined
  profiles ??= new VolumeProfiles(decodeVolume(loadedVolume))
  const profile = profiles.at(store.lat[i]!, store.lon[i]!, store.sizeKm[i]!)
  return profile && levelNear(profile, altitudeM / ftToM / exaggeration.value, lo, hi)
}

// Same object until the visible columns change, so streaming aircraft updates don't make deck.gl rebuild every column.
const columnData = computed(() => ({ length: visible.value.count }))

let loadedFrame: string | undefined
/** The drawn frame's volume, and its per-level EDR for the tooltip (built on the first hover over a stacked column). */
let loadedVolume: ArrayBuffer | undefined
let profiles: VolumeProfiles | undefined
let worker: Worker | undefined
async function loadVolume() {
  const frame = props.frame
  const thresholds = props.status?.thresholds.Medium
  if (!frame || !thresholds || loadedFrame === frame.id) return
  loadedFrame = frame.id
  worker?.terminate()
  worker = undefined
  loadState.value = 'Loading turbulence…'
  loadProgress.value = undefined
  let volume: ArrayBuffer
  try {
    volume = await prefetchVolume(frame.id)
  } catch (e) {
    console.warn('volume load failed', e)
    if (loadedFrame === frame.id) {
      loadState.value = ''
      loadedFrame = undefined
    }
    return
  }
  // Another frame was picked, or 3D closed, while this one downloaded.
  if (loadedFrame !== frame.id || !deck) return
  const current = new VolumeWorker()
  worker = current
  let total = 0
  let received = 0
  current.onmessage = (e: MessageEvent<VolumeMessage>) => {
    if (worker !== current) return
    const m = e.data
    if (m.type === 'meta') {
      // A newer frame: drop the old columns only now, so the scene doesn't go blank while downloading.
      store = emptyBatch(m.total)
      storeLength = 0
      loadedVolume = volume
      profiles = undefined
      total = m.total
    } else if (m.type === 'batch') {
      // Timings for measuring how fast 3D opens (performance.getEntriesByName('3d-first-blocks')).
      if (!received) performance.mark('3d-first-blocks')
      append(m.batch)
      received += m.batch.lat.length
      loadState.value = `Drawing turbulence… ${Math.round((received / total) * 100)}%`
      loadProgress.value = (received / total) * 100
    } else {
      if (m.type === 'done') performance.mark('3d-all-blocks')
      if (m.type === 'error') console.warn('volume load failed', m.message)
      loadState.value = ''
      current.terminate()
      worker = undefined
    }
  }
  const request: VolumeRequest = {
    volume,
    // Plain copies: the reactive props are proxies, which can't cross into the worker.
    thresholds: { ...toRaw(thresholds) },
    center: [Number(viewState.longitude), Number(viewState.latitude)]
  }
  current.postMessage(request)
}

/** Sets tilt and rotation directly (used while the shift+drag that opened 3D is still going). */
function setRotation(pitch: number, bearing: number) {
  viewState = { ...viewState, pitch: Math.min(Math.max(pitch, 0), maxPitch), bearing }
  deck?.setProps({ initialViewState: viewState })
}

/** Moves the camera by screen pixels (the W/A/S/D keys), as if the ground were dragged the other way. */
function panBy(dx: number, dy: number) {
  const el = canvasEl.value
  if (!deck || !el) return
  const { clientWidth: width, clientHeight: height } = el
  const viewport = new WebMercatorViewport({ ...viewState, width, height })
  const [longitude, latitude] = viewport.unproject([width / 2 + dx, height / 2 + dy])
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return
  viewState = {
    ...viewState,
    longitude: longitude!,
    latitude: Math.max(Math.min(latitude!, 85), -85)
  }
  deck.setProps({ initialViewState: viewState })
  window.clearTimeout(moveTimer)
  moveTimer = window.setTimeout(() => emit('move', fromDeck(viewState)), 400)
}

/** Turns the camera by `degrees`, positive turning the map counter-clockwise (the Q and E keys). */
function rotateBy(degrees: number) {
  if (!deck) return
  viewState = { ...viewState, bearing: (((viewState.bearing ?? 0) + degrees + 540) % 360) - 180 }
  deck.setProps({ initialViewState: viewState })
}

function animateTo(pitch: number, bearing: number, duration = 600): Promise<MapView> {
  return new Promise((resolve) => {
    if (!deck) return resolve(fromDeck(viewState))
    viewState = {
      ...viewState,
      pitch,
      bearing,
      transitionDuration: duration,
      transitionInterpolator: new LinearInterpolator(['pitch', 'bearing'])
    } as MapViewState
    deck.setProps({ initialViewState: viewState })
    window.setTimeout(() => resolve(fromDeck(viewState)), duration + 30)
  })
}

/** Tilts up from flat into a 3D view. */
const tiltUp = () => animateTo(50, viewState.bearing === 0 ? -15 : (viewState.bearing ?? 0))
/** Animates back to flat and north-up, then gives the view to hand back to the 2D map. */
const flatten = () => animateTo(0, 0, 450)

defineExpose({ setRotation, tiltUp, flatten, panBy, rotateBy })

// Clicks are picked by hand (deck's onClick doesn't fire in Chromium with deck.gl 9.4).
let downAt: [number, number] = [0, 0]
const onPointerDown = (e: PointerEvent) => (downAt = [e.offsetX, e.offsetY])
function onCanvasClick(e: MouseEvent) {
  if (!deck || Math.hypot(e.offsetX - downAt[0], e.offsetY - downAt[1]) > 4) return
  const info = deck.pickObject({ x: e.offsetX, y: e.offsetY, radius: 4, layerIds: ['aircraft'] })
  const a = info?.object as AircraftView | undefined
  if (a) emit('select', a.hex)
}

// Frames drawn in the last second; deck only redraws when something changes, so an idle scene reads 0.
let frames = 0
let fpsTimer: number | undefined
let moveTimer: number | undefined

watch(
  () => [props.frame?.id, props.status?.thresholds],
  () => loadVolume()
)
watch(
  [
    visible,
    () => props.aircraft,
    () => props.status,
    () => props.levelFt,
    () => props.route,
    exaggeration,
    corridorShown,
    whatIfPoints,
    hoverMinute
  ],
  render
)
watch(() => props.route, loadCorridor)

let overAircraft = false

onMounted(() => {
  viewState = toDeck(props.view)
  deck = new Deck({
    parent: canvasEl.value,
    // luma.gl otherwise waits for the window's load event, which a first visit's tiles and images can hold back for
    // longer than a minute: the 3D view stayed blank until then.
    deviceProps: { waitForPageLoad: false },
    initialViewState: viewState,
    // touchRotate: two-finger twist rotates and two-finger vertical slide tilts.
    controller: { touchRotate: true },
    layers: buildLayers(),
    getTooltip: tooltip,
    // Voxels are pickable only for their tooltip; keep the crosshair over them and show the pointer on aircraft.
    onHover: (info: PickingInfo) => {
      overAircraft = info.layer?.id === 'aircraft'
      if (info.layer?.id === 'route' && info.object)
        hoverMinute.value =
          props.route?.points[(info.object as RouteSegment).point]?.minutesFromStart
    },
    getCursor: ({ isDragging }: { isDragging: boolean }) => deckCursor(isDragging, overAircraft),
    onViewStateChange: ({ viewState: v }) => {
      viewState = v as MapViewState
      window.clearTimeout(moveTimer)
      moveTimer = window.setTimeout(() => emit('move', fromDeck(viewState)), 400)
    },
    onAfterRender: () => frames++
  })
  canvasEl.value!.addEventListener('pointerdown', onPointerDown)
  canvasEl.value!.addEventListener('click', onCanvasClick)
  fpsTimer = window.setInterval(() => {
    if (frames > 0) fps.value = frames
    frames = 0
  }, 1000)
  loadVolume()
  loadCorridor()
})

onBeforeUnmount(() => {
  worker?.terminate()
  window.clearInterval(fpsTimer)
  window.clearTimeout(moveTimer)
  deck?.finalize()
  deck = undefined
})
</script>

<style scoped>
.route-chip {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 2;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  max-width: min(560px, calc(100% - 32px));
  padding: 8px 6px 8px 12px;
  color: #fff;
}
.route-chip.mobile {
  /* Under the phone's top panels, between the level rail and the 2D/3D switch. */
  top: auto;
  bottom: 150px;
  left: 12px;
  right: 12px;
  transform: none;
  max-width: none;
}
.route-text {
  min-width: 0;
}
.route-label {
  font-weight: 600;
}
.route-path {
  font-size: 12px;
}
.map3d {
  position: absolute;
  inset: 0;
  z-index: 900;
  background: #e5e7eb;
}
.canvas {
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
.panel-fab {
  position: absolute;
  z-index: 1000;
  /* Above the legend, clear of the flight-level rail on the right. */
  bottom: 56px;
  left: 8px;
}
.controls3d.sheet {
  background: rgba(24, 39, 52, 0.92);
  left: 8px;
  right: 8px;
  bottom: 8px;
  width: auto;
  z-index: 1100;
}
/* Desktop: a block in the live map's bottom-left column, above the legend; it scrolls when the window is short. */
.controls3d:not(.sheet) {
  position: relative;
  z-index: auto;
  flex: 0 1 auto;
  min-height: 0;
  overflow-y: auto;
}
/* Room between the switch and its label: the switch's thumb pokes past its own box. */
.controls3d :deep(.v-switch .v-label) {
  padding-left: 16px;
}
.projections {
  /* Along the bottom, between the left column and the 2D/3D switch. */
  position: absolute;
  z-index: 1000;
  left: 296px;
  right: 120px;
  bottom: 12px;
  max-width: 900px;
  margin: 0 auto;
  padding: 8px 12px;
}
.loading {
  position: absolute;
  z-index: 1000;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 12px;
  font-size: 12px;
  min-width: 200px;
}
</style>
