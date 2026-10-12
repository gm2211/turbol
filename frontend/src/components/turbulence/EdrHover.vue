<template>
  <div v-if="tip" class="edr-tip" :style="{ left: `${tip.x}px`, top: `${tip.y}px` }">
    <template v-if="tip.loading">
      <b>EDR <span class="pending">…</span></b> · <span class="pending">…</span> · {{ levelLabel }}
    </template>
    <template v-else-if="tip.edr != null">
      <b>EDR {{ tip.edr.toFixed(2) }}</b> ·
      <span :style="{ color: categoryColors[tip.category] }">{{
        categoryLabels[tip.category]
      }}</span>
      · {{ levelLabel }}
      <span v-if="tip.coarse" class="coarse">· WAFS/GFS ~28 km</span>
    </template>
    <template v-else>No data · {{ levelLabel }}</template>
  </div>
</template>

<script setup lang="ts">
/**
 * Cursor-following EDR readout for the live map. Looks up the GTG (outside it, global) column under the cursor
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
  /** From the coarse global layer outside GTG. */
  coarse?: boolean
}

const tip = ref<Tip>()
/** In-flight or finished lookups, and the finished ones by cell so a revisit shows instantly. */
const pending = new Map<string, Promise<TurbulenceColumn | undefined>>()
const resolved = new Map<string, TurbulenceColumn>()
const CELL_DEG = 0.1
let timer: number | undefined
let lastLatLng: L.LatLng | undefined
/** Cell (frame + snapped lat/lon) currently under the pointer; results for any other cell are dropped. */
let currentKey: string | undefined

interface Cell {
  key: string
  frameId: string
  lat: number
  lon: number
}

function cellAt(frameId: string, latlng: L.LatLng): Cell {
  const lon = ((((latlng.lng + 180) % 360) + 360) % 360) - 180
  const la = Math.round(latlng.lat / CELL_DEG) * CELL_DEG
  const lo = Math.round(lon / CELL_DEG) * CELL_DEG
  return { key: `${frameId}:${la.toFixed(1)}:${lo.toFixed(1)}`, frameId, lat: la, lon: lo }
}

function fetchCell(cell: Cell) {
  let p = pending.get(cell.key)
  if (!p) {
    if (pending.size > 5000) {
      pending.clear()
      resolved.clear()
    }
    p = fetchColumn(cell.frameId, cell.lat, cell.lon).then(
      (col) => {
        resolved.set(cell.key, col)
        return col
      },
      () => {
        pending.delete(cell.key)
        return undefined
      }
    )
    pending.set(cell.key, p)
  }
  return p
}

function render(x: number, y: number, col: TurbulenceColumn | undefined, loading: boolean) {
  const level = col?.levels.find((l) => l.levelFt === props.levelFt)
  tip.value = {
    x,
    y,
    loading,
    edr: level?.edr,
    category: level?.category ?? 'NoData',
    coarse: col?.source === 'Global'
  }
}

async function lookup(cell: Cell) {
  const col = await fetchCell(cell)
  if (cell.key !== currentKey || !tip.value) return
  render(tip.value.x, tip.value.y, col, false)
}

/** Show the tooltip at the pointer: the cell's value if known, else the placeholder until it loads. */
function update(latlng: L.LatLng, x: number, y: number, delay: number) {
  lastLatLng = latlng
  window.clearTimeout(timer)
  const frameId = props.frameId
  if (!frameId) return
  const cell = cellAt(frameId, latlng)
  const known = resolved.get(cell.key)
  if (known) {
    currentKey = cell.key
    render(x, y, known, false)
  } else if (cell.key !== currentKey || tip.value?.loading !== false) {
    currentKey = cell.key
    render(x, y, undefined, true)
    timer = window.setTimeout(() => lookup(cell), delay)
  } else {
    tip.value = { ...tip.value, x, y }
  }
}

function show(e: L.LeafletMouseEvent, delay: number) {
  update(e.latlng, e.containerPoint.x, e.containerPoint.y, delay)
}

function hide() {
  window.clearTimeout(timer)
  currentKey = undefined
  tip.value = undefined
}

const onMove = (e: L.LeafletMouseEvent) => show(e, 60)
const onTap = (e: L.LeafletMouseEvent) => {
  if ((e.originalEvent as PointerEvent).pointerType === 'mouse') return
  show(e, 0)
}

function refresh() {
  if (!tip.value || !lastLatLng) return
  currentKey = undefined
  update(lastLatLng, tip.value.x, tip.value.y, 0)
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
.coarse {
  opacity: 0.7;
}
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
.pending {
  opacity: 0.6;
}
</style>
