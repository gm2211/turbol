<template>
  <div class="map3d">
    <div ref="canvasEl" class="canvas" @contextmenu.prevent />

    <div class="panel glass-panel controls3d">
      <div class="d-flex align-center justify-space-between">
        <span class="panel-title">3D</span>
        <span class="muted fps" v-if="fps">{{ fps }} fps</span>
      </div>
      <div class="muted" v-if="loadingVolume">Loading the turbulence volume…</div>
      <div class="muted" v-else-if="volume">
        {{ visibleVoxels.length.toLocaleString() }} rough blocks shown
      </div>
      <v-select
        v-model="windowFt"
        :items="windowItems"
        :label="`Turbulence around ${flightLevel(levelFt)}`"
        density="compact"
        hide-details
        variant="outlined"
        theme="dark"
        class="my-2"
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
      <div class="hint">
        Shift+drag or right-drag to tilt and rotate · click a plane for details
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import {
  Deck,
  type Layer,
  LinearInterpolator,
  type MapViewState,
  type PickingInfo
} from '@deck.gl/core'
import { IconLayer, LineLayer, PathLayer } from '@deck.gl/layers'
import { SimpleMeshLayer } from '@deck.gl/mesh-layers'
import { CubeGeometry } from '@luma.gl/engine'
import {
  type AircraftView,
  type FrameInfo,
  type TurbulenceStatus,
  type TurbulenceVolume,
  categoryColors,
  categoryLabels,
  fetchVolume,
  flightLevel,
  overlayRgba
} from '@/api/turbulence'
import {
  type Voxel,
  basemapLayer,
  blue,
  ftToM,
  hexRgb,
  toVoxels,
  tooltipStyle
} from '@/components/turbulence/scene3d'

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
}>()
const emit = defineEmits<{
  /** The camera settled somewhere new. */
  move: [view: MapView]
  /** A plane was clicked. */
  select: [hex: string]
}>()

const canvasEl = ref<HTMLDivElement>()
const volume = shallowRef<TurbulenceVolume>()
const voxels = shallowRef<Voxel[]>([])
const loadingVolume = ref(false)
const windowFt = ref(4000)
const exaggeration = ref(30)
const fps = ref(0)

const windowItems = [
  { title: '± 2,000 ft', value: 2000 },
  { title: '± 4,000 ft', value: 4000 },
  { title: '± 8,000 ft', value: 8000 }
]

const z = (ft: number) => ft * ftToM * exaggeration.value
const maxPitch = 85

const visibleVoxels = computed(() => {
  const lo = props.levelFt - windowFt.value
  const hi = props.levelFt + windowFt.value
  return voxels.value.filter((v) => v.topFt >= lo && v.bottomFt <= hi)
})

const planeSvg =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="48" height="48" viewBox="0 0 24 24"><path fill="#fff" d="M12 2c.6 0 1 .5 1 1.2V9l8 4.6v2l-8-2.4v5.3l2.3 1.7V22L12 21l-3.3 1v-1.8L11 18.5v-5.3L3 15.6v-2L11 9V3.2C11 2.5 11.4 2 12 2z"/></svg>'
  )
const planeIconDef = { url: planeSvg, width: 48, height: 48, mask: true }

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

const categoryRgba = (a: AircraftView, alpha: number): [number, number, number, number] => [
  ...hexRgb(categoryColors[a.category]),
  alpha
]

function buildLayers(): Layer[] {
  const layers: Layer[] = [basemapLayer()]
  const c = props.status?.coverage
  if (c) {
    const h = z(props.levelFt)
    const ring = [
      [c.west, c.south, h],
      [c.east, c.south, h],
      [c.east, c.north, h],
      [c.west, c.north, h],
      [c.west, c.south, h]
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
  const flying = props.aircraft.filter((a) => a.altitudeFt !== undefined)
  layers.push(
    // A stalk from the ground to each plane shows its altitude.
    new LineLayer<AircraftView>({
      id: 'aircraft-stalks',
      data: flying,
      getSourcePosition: (a) => [a.lon, a.lat, 0],
      getTargetPosition: (a) => [a.lon, a.lat, z(a.onGround ? 0 : (a.altitudeFt ?? 0))],
      getColor: (a) => categoryRgba(a, 90),
      getWidth: 1,
      widthUnits: 'pixels',
      updateTriggers: { getTargetPosition: exaggeration.value }
    }),
    new IconLayer<AircraftView>({
      id: 'aircraft',
      data: flying,
      getIcon: () => planeIconDef,
      getPosition: (a) => [a.lon, a.lat, z(a.onGround ? 0 : (a.altitudeFt ?? 0))],
      // Lying flat at its altitude, nose along its track (deck angles turn counter-clockwise).
      billboard: false,
      getAngle: (a) => -(a.trackDeg ?? 0),
      getSize: 20,
      sizeUnits: 'pixels',
      getColor: (a) => categoryRgba(a, 255),
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
  const v = info.object as Voxel | undefined
  if (!v) return null
  return {
    html: `<b>${flightLevel(v.levelFt)}</b> · ${categoryLabels[v.category]} (EDR ${v.edr.toFixed(2)})`,
    style: tooltipStyle
  }
}

let loadedFrame: string | undefined
async function loadVolume() {
  const frame = props.frame
  const thresholds = props.status?.thresholds.Medium
  if (!frame || !thresholds || (volume.value && loadedFrame === frame.id)) return
  loadingVolume.value = true
  try {
    const v = await fetchVolume(frame.id)
    if (props.frame?.id !== frame.id) return
    loadedFrame = frame.id
    volume.value = v
    voxels.value = toVoxels(v, thresholds)
  } catch (e) {
    console.warn('volume fetch failed', e)
  } finally {
    loadingVolume.value = false
  }
}

/** Sets tilt and rotation directly (used while the shift+drag that opened 3D is still going). */
function setRotation(pitch: number, bearing: number) {
  viewState = { ...viewState, pitch: Math.min(Math.max(pitch, 0), maxPitch), bearing }
  deck?.setProps({ initialViewState: viewState })
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

defineExpose({ setRotation, tiltUp, flatten })

// Clicks are picked by hand like the /3d view (deck's onClick doesn't fire in Chromium with deck.gl 9.4).
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
  [visibleVoxels, () => props.aircraft, () => props.status, () => props.levelFt, exaggeration],
  render
)

onMounted(() => {
  viewState = toDeck(props.view)
  deck = new Deck({
    parent: canvasEl.value,
    initialViewState: viewState,
    controller: true,
    layers: buildLayers(),
    getTooltip: tooltip,
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
.controls3d {
  right: 12px;
  bottom: 64px;
  width: 250px;
}
</style>
