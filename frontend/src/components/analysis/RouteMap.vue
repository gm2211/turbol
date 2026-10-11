<template>
  <div ref="mapEl" class="route-map" />
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Place, RoutePoint } from '@/api/flights'
import type { AircraftView } from '@/api/turbulence'
import { categoryColors, categoryLabels, flightLevel, tileUrl } from '@/api/turbulence'
import { planeIcon } from '@/components/turbulence/planeIcon'
import { baseMapAttribution, baseMapUrl } from '@/components/turbulence/baseMap'

const props = defineProps<{
  points: RoutePoint[]
  origin?: Place
  destination?: Place
  aircraft?: AircraftView
  trail?: [number, number][]
  worst?: [number, number]
  worstLabel?: string
  follow?: boolean
  /** Show a key explaining the ribbon, the worst-bump ring and the aircraft. */
  legend?: boolean
  overlay?: { frameId: string; levelFt: number }
}>()

const mapEl = ref<HTMLDivElement>()
let map: L.Map | undefined
const layers = L.layerGroup()
let fitted = false
let overlayLayer: L.TileLayer | undefined
let overlayKey = ''
let legendControl: L.Control | undefined

/** Optional turbulence tiles (e.g. the nowcast at the followed aircraft's altitude). */
function drawOverlay() {
  if (!map) return
  const key = props.overlay ? `${props.overlay.frameId}/${props.overlay.levelFt}` : ''
  if (key === overlayKey) return
  overlayKey = key
  if (overlayLayer) map.removeLayer(overlayLayer)
  overlayLayer = props.overlay
    ? L.tileLayer(tileUrl(props.overlay.frameId, props.overlay.levelFt), { opacity: 0.7, maxNativeZoom: 9 }).addTo(map)
    : undefined
}

const worstColor = '#dc2626'

/** A key for the ribbon colours, the worst-bump ring and the live aircraft. */
function drawLegend() {
  if (!map) return
  legendControl?.remove()
  legendControl = undefined
  if (!props.legend || props.points.length === 0) return
  const ribbon = [...new Set(props.points.map((p) => p.category))]
    .map((c) => `<span class="ribbon" style="background:${categoryColors[c]}"></span>${categoryLabels[c]}`)
    .join(' ')
  const rows = [`<div>Forecast ${ribbon}</div>`, `<div class="muted">Great-circle route: filed routes aren't public</div>`]
  if (props.worst) {
    rows.push(`<div><span class="ring" style="border-color:${worstColor}"></span>${props.worstLabel ?? 'Worst bump'}</div>`)
  }
  const a = props.aircraft
  if (a) {
    const icon = planeIcon(a.category, 45, 14, true).options.html as string
    rows.push(`<div><span class="plane">${icon}</span>${a.callsign ?? a.hex} now, ${flightLevel(a.altitudeFt)}</div>`)
  }
  const control = new L.Control({ position: 'bottomleft' })
  control.onAdd = () => {
    const el = L.DomUtil.create('div', 'route-legend')
    el.innerHTML = rows.join('')
    return el
  }
  legendControl = control.addTo(map)
}

function draw() {
  if (!map) return
  drawOverlay()
  layers.clearLayers()
  // One polyline per run of same-category points, so the route reads like a turbulence ribbon.
  const pts = props.points
  let start = 0
  for (let i = 1; i <= pts.length; i++) {
    if (i === pts.length || pts[i].category !== pts[start].category) {
      const run = pts.slice(start, Math.min(i + 1, pts.length)).map((p) => [p.lat, p.lon] as [number, number])
      L.polyline(run, { color: categoryColors[pts[start].category], weight: 6, opacity: 0.9 }).addTo(layers)
      start = i
    }
  }
  if (props.trail && props.trail.length > 1) {
    L.polyline(props.trail, { color: '#334155', weight: 3, dashArray: '4 6', opacity: 0.8 }).addTo(layers)
  }
  // Airport labels go on the far side from the other airport so the route never runs under them.
  const ends = [
    [props.origin, props.destination],
    [props.destination, props.origin]
  ] as const
  for (const [p, other] of ends) {
    if (p) {
      const below = other !== undefined && other.lat > p.lat
      L.circleMarker([p.lat, p.lon], { radius: 6, color: '#0f172a', fillColor: '#fff', fillOpacity: 1, weight: 2 })
        .bindTooltip(p.code, { permanent: true, direction: below ? 'bottom' : 'top', offset: [0, below ? 6 : -6] })
        .addTo(layers)
    }
  }
  if (props.worst) {
    L.circleMarker(props.worst, { radius: 9, color: worstColor, weight: 3, fillOpacity: 0 })
      .bindTooltip(props.worstLabel ?? 'Worst bump', { direction: 'right', offset: [10, 0] })
      .addTo(layers)
  }
  const a = props.aircraft
  if (a) {
    L.marker([a.lat, a.lon], { icon: planeIcon(a.category, a.trackDeg ?? 0, 30, true), zIndexOffset: 1000 })
      .bindTooltip(`${a.callsign ?? a.hex} · ${flightLevel(a.altitudeFt)}`, { direction: 'right', offset: [14, 0] })
      .addTo(layers)
  }
  drawLegend()
  if (props.follow && a) {
    map.panTo([a.lat, a.lon], { animate: true })
  } else if (!fitted && pts.length > 1) {
    map.fitBounds(L.latLngBounds(pts.map((p) => [p.lat, p.lon] as [number, number])), { padding: [30, 30] })
    fitted = true
  }
}

watch(
  () => [props.points, props.aircraft, props.trail, props.overlay, props.worst, props.worstLabel],
  () => {
    if (!props.follow) fitted = false
    draw()
  }
)

onMounted(() => {
  map = L.map(mapEl.value!, { worldCopyJump: true }).setView([39.5, -97], props.follow ? 6 : 4)
  L.tileLayer(baseMapUrl, { attribution: baseMapAttribution, maxZoom: 12 }).addTo(map)
  layers.addTo(map)
  draw()
})
onBeforeUnmount(() => map?.remove())
</script>

<style scoped>
.route-map {
  width: 100%;
  height: 100%;
  min-height: 320px;
  border-radius: 8px;
}
:deep(.route-legend) {
  background: rgba(255, 255, 255, 0.92);
  color: #0f172a;
  border-radius: 6px;
  padding: 6px 8px;
  font-size: 12px;
  line-height: 1.7;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
}
:deep(.route-legend .muted) {
  color: #475569;
}
:deep(.route-legend .ribbon) {
  display: inline-block;
  width: 16px;
  height: 5px;
  border-radius: 2px;
  margin: 0 4px 2px 0;
  vertical-align: middle;
}
:deep(.route-legend .ring) {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2.5px solid;
  border-radius: 50%;
  margin-right: 5px;
  vertical-align: middle;
}
:deep(.route-legend .plane) {
  display: inline-block;
  margin-right: 4px;
  vertical-align: middle;
}
:deep(.route-legend .plane svg) {
  display: block;
}
:deep(.plane-icon) {
  background: none;
  border: none;
}
</style>
