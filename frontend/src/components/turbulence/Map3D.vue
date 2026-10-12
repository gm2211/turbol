<template>
  <div class="map3d">
    <div ref="canvasEl" class="canvas" @contextmenu.prevent />

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
        <div class="muted" v-else-if="storeVersion">
          {{ visible.count.toLocaleString() }} turbulence columns shown
        </div>
        <div class="muted">Pick the levels on the flight-level rail</div>
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
import { computed, onBeforeUnmount, onMounted, ref, toRaw, watch } from 'vue'
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
}>()
const emit = defineEmits<{
  /** The camera settled somewhere new. */
  move: [view: MapView]
  /** A plane was clicked. */
  select: [hex: string]
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
  maxPitch
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
  const flying = props.aircraft.filter((a) => a.altitudeFt !== undefined)
  const ringed = flying.filter((a) => ringColor(a.category))
  layers.push(
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
  )
  return layers
}

function render() {
  deck?.setProps({ layers: buildLayers() })
}

function tooltip(info: PickingInfo) {
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
  [visible, () => props.aircraft, () => props.status, () => props.levelFt, exaggeration],
  render
)

onMounted(() => {
  viewState = toDeck(props.view)
  deck = new Deck({
    parent: canvasEl.value,
    initialViewState: viewState,
    // touchRotate: two-finger twist rotates and two-finger vertical slide tilts.
    controller: { touchRotate: true },
    layers: buildLayers(),
    getTooltip: tooltip,
    getCursor: deckCursor,
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
  width: 250px;
  flex: 0 1 auto;
  min-height: 0;
  overflow-y: auto;
}
/* Room between the switch and its label: the switch's thumb pokes past its own box. */
.controls3d :deep(.v-switch .v-label) {
  padding-left: 16px;
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
