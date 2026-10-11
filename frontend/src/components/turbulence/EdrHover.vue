<template>
  <div v-if="tip" class="edr-tip" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">
    <template v-if="tip.loading">…</template>
    <template v-else-if="tip.edr != null">
      <b>EDR {{ tip.edr.toFixed(2) }}</b> ·
      <span :style="{ color: categoryColors[tip.category] }">{{
        categoryLabels[tip.category]
      }}</span>
      · {{ levelLabel }}
    </template>
    <template v-else>No data · {{ levelLabel }}</template>
  </div>
</template>

<script setup lang="ts">
/**
 * Cursor-following EDR readout for the live map. Looks up the GTG grid column under the cursor
 * (debounced, cached per frame and ~0.1° cell, all levels at once so switching altitude is free).
 * On touch devices a tap shows it at the tap point.
 */
import type L from 'leaflet'
import { onBeforeUnmount, ref, watch } from 'vue'
import {
  type TurbulenceCategory,
  type TurbulenceColumn,
  categoryColors,
  categoryLabels,
  fetchColumn
} from '@/api/turbulence'

const props = defineProps<{ map?: L.Map; frameId?: string; levelFt: number; levelLabel: string }>()

interface Tip {
  x: number
  y: number
  loading: boolean
  edr?: number
  category: TurbulenceCategory
}

const tip = ref<Tip>()
const cache = new Map<string, Promise<TurbulenceColumn | undefined>>()
const CELL_DEG = 0.1
let timer: number | undefined
let lastLatLng: L.LatLng | undefined
let seq = 0

function column(frameId: string, lat: number, lon: number) {
  const la = Math.round(lat / CELL_DEG) * CELL_DEG
  const lo = Math.round(lon / CELL_DEG) * CELL_DEG
  const key = `${frameId}:${la.toFixed(1)}:${lo.toFixed(1)}`
  let p = cache.get(key)
  if (!p) {
    if (cache.size > 5000) cache.clear()
    p = fetchColumn(frameId, la, lo).catch(() => {
      cache.delete(key)
      return undefined
    })
    cache.set(key, p)
  }
  return p
}

async function lookup(latlng: L.LatLng, point: L.Point) {
  const frameId = props.frameId
  if (!frameId) return
  const mySeq = ++seq
  const lon = ((((latlng.lng + 180) % 360) + 360) % 360) - 180
  const col = await column(frameId, latlng.lat, lon)
  if (mySeq !== seq || !tip.value) return
  const level = col?.levels.find((l) => l.levelFt === props.levelFt)
  tip.value = {
    x: point.x,
    y: point.y,
    loading: false,
    edr: level?.edr,
    category: level?.category ?? 'NoData'
  }
}

function show(e: L.LeafletMouseEvent, delay: number) {
  lastLatLng = e.latlng
  const prev = tip.value
  tip.value = {
    ...(prev ?? { loading: true, category: 'NoData' }),
    x: e.containerPoint.x,
    y: e.containerPoint.y
  }
  window.clearTimeout(timer)
  timer = window.setTimeout(() => lookup(e.latlng, e.containerPoint), delay)
}

function hide() {
  window.clearTimeout(timer)
  seq++
  tip.value = undefined
}

const onMove = (e: L.LeafletMouseEvent) => show(e, 60)
const onTap = (e: L.LeafletMouseEvent) => {
  if ((e.originalEvent as PointerEvent).pointerType === 'mouse') return
  show(e, 0)
}

function refresh() {
  if (tip.value && lastLatLng && props.map)
    lookup(lastLatLng, props.map.latLngToContainerPoint(lastLatLng))
}

watch(
  () => props.map,
  (map, old) => {
    old?.off('mousemove', onMove).off('mouseout', hide).off('movestart', hide).off('click', onTap)
    map?.on('mousemove', onMove).on('mouseout', hide).on('movestart', hide).on('click', onTap)
  },
  { immediate: true }
)
watch(() => [props.frameId, props.levelFt], refresh)

onBeforeUnmount(() => {
  window.clearTimeout(timer)
  props.map
    ?.off('mousemove', onMove)
    .off('mouseout', hide)
    .off('movestart', hide)
    .off('click', onTap)
})
</script>

<style scoped>
.edr-tip {
  position: absolute;
  z-index: 1001;
  transform: translate(14px, 14px);
  pointer-events: none;
  white-space: nowrap;
  padding: 4px 8px;
  font-size: 12px;
  border-radius: 6px;
  color: #fff;
  background: rgba(15, 23, 42, 0.85);
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.3);
}
</style>
