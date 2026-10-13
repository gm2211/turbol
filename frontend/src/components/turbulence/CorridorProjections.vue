<template>
  <div class="corridor" :class="{ dark }">
    <div class="controls">
      <div class="control">
        <div class="label">
          {{ shiftLabel }}
        </div>
        <v-slider
          :model-value="whatIf.shiftFt"
          :min="corridor.levelsFt[0]"
          :max="corridor.levelsFt.at(-1)"
          :step="levelStep"
          hide-details
          density="compact"
          :theme="dark ? 'dark' : undefined"
          color="var(--my-accent)"
          aria-label="Fly higher or lower"
          @update:model-value="set({ shiftFt: $event })"
        />
      </div>
      <div class="control">
        <div class="label">{{ offsetLabel }}</div>
        <v-slider
          :model-value="whatIf.offsetKm"
          :min="corridor.offsetsKm[0]"
          :max="corridor.offsetsKm.at(-1)"
          :step="offsetStep"
          hide-details
          density="compact"
          :theme="dark ? 'dark' : undefined"
          color="var(--my-accent)"
          aria-label="Detour left or right"
          @update:model-value="set({ offsetKm: $event })"
        />
      </div>
    </div>
    <div class="summary">
      <span>
        Moderate or worse: <b>{{ fmtMin(roughMinutes(planned)) }}</b> as planned<template
          v-if="changed"
        >
          → <b :class="better">{{ fmtMin(roughMinutes(current)) }}</b> {{ changeLabel }}</template
        >
        · Light: {{ fmtMin(changed ? current[1]! : planned[1]!) }}
      </span>
      <button
        v-if="best && !isBest"
        type="button"
        class="best"
        :title="`Fewest rough minutes within ${corridor.levelsFt.at(-1)!.toLocaleString()} ft and ${corridor.offsetsKm.at(-1)} km`"
        @click="set(best.whatIf)"
      >
        Smoothest nearby: {{ describe(best.whatIf) }} ({{ fmtMin(roughMinutes(best.minutes)) }}
        moderate+)
      </button>
      <button v-if="changed" type="button" class="best" @click="set(noChange)">As planned</button>
    </div>
    <div ref="wrapEl" class="views">
      <div class="view-label">Side view: the flight and the air above and below it</div>
      <canvas
        ref="sideEl"
        :style="{ height: `${sideH}px` }"
        @pointerdown="onPointer"
        @pointermove="onPointer"
        @pointerleave="onLeave"
      />
      <div class="view-label">Top view: either side of the track, at the flight's level</div>
      <canvas
        ref="topEl"
        :style="{ height: `${topH}px` }"
        @pointerdown="onPointer"
        @pointermove="onPointer"
        @pointerleave="onLeave"
      />
      <div v-if="hovered" class="readout" :style="readoutStyle">
        {{ clock(hovered.point.time) }} · {{ flightLevel(hovered.path.altitudeFt) }}
        <template v-if="hovered.path.offset !== offset0">
          · {{ sideKm(corridor.offsetsKm[hovered.path.offset]!) }}</template
        >
        ·
        <span class="swatch" :style="{ background: categoryColors[hovered.category] }" />
        {{ categoryLabels[hovered.category] }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  type Thresholds,
  type TurbulenceCategory,
  categoryColors,
  categoryLabels,
  flightLevel
} from '@/api/turbulence'
import { type RoutePoint, formatDuration, pointAt } from '@/api/flights'
import {
  type Corridor,
  type WhatIf,
  bestWhatIf,
  corridorCategories,
  corridorCategory,
  minutesByCategory,
  noChange,
  roughMinutes,
  whatIfPath
} from '@/components/turbulence/corridor'
import { hexRgb } from '@/components/turbulence/scene3d'

/**
 * Two flat views of the air around a flight, kept in step with its timeline: a side view (time along the route,
 * altitude up) and a top view (time along the route, either side of the track), each colored by the corridor cells
 * as felt by the flight's aircraft. Sliders try the flight higher or lower or on a detour, and say how much rough
 * air that saves.
 */
const props = withDefaults(
  defineProps<{
    points: RoutePoint[]
    corridor: Corridor
    /** How strongly the aircraft feels turbulence (1 for a medium aircraft). */
    factor: number
    thresholds: Thresholds
    whatIf: WhatIf
    hoverMinute?: number
    dark?: boolean
  }>(),
  { dark: false }
)
const emit = defineEmits<{
  hover: [minute: number | undefined]
  'update:whatIf': [whatIf: WhatIf]
}>()

const sideH = 120
const topH = 64
// The timeline's own margins, so its cursor and these views' line up.
const padL = 8
const padR = 8

const levelStep = computed(() => props.corridor.levelsFt[1]! - props.corridor.levelsFt[0]!)
const offsetStep = computed(() => props.corridor.offsetsKm[1]! - props.corridor.offsetsKm[0]!)
const offset0 = computed(() => props.corridor.offsetsKm.indexOf(0))

const set = (change: Partial<WhatIf>) => emit('update:whatIf', { ...props.whatIf, ...change })
const changed = computed(() => props.whatIf.shiftFt !== 0 || props.whatIf.offsetKm !== 0)

const planned = computed(() =>
  minutesByCategory(
    props.corridor,
    props.points,
    whatIfPath(props.corridor, props.points, noChange),
    props.factor,
    props.thresholds
  )
)
const path = computed(() => whatIfPath(props.corridor, props.points, props.whatIf))
const current = computed(() =>
  minutesByCategory(props.corridor, props.points, path.value, props.factor, props.thresholds)
)
const best = computed(() =>
  bestWhatIf(props.corridor, props.points, props.factor, props.thresholds)
)
const isBest = computed(
  () =>
    !best.value ||
    (roughMinutes(best.value.minutes) >=
      roughMinutes(changed.value ? current.value : planned.value) &&
      best.value.minutes[1]! >= (changed.value ? current.value : planned.value)[1]!)
)
const better = computed(() => {
  const d = roughMinutes(current.value) - roughMinutes(planned.value)
  return d < 0 ? 'good' : d > 0 ? 'bad' : ''
})

const fmtMin = (m: number) => (m < 0.5 ? '0 min' : formatDuration(m))
const sideKm = (km: number) => `${Math.abs(km)} km ${km < 0 ? 'left' : 'right'}`
const shiftLabel = computed(() =>
  props.whatIf.shiftFt === 0
    ? 'Altitude: as planned'
    : `Fly ${Math.abs(props.whatIf.shiftFt).toLocaleString()} ft ${props.whatIf.shiftFt > 0 ? 'higher' : 'lower'}`
)
const offsetLabel = computed(() =>
  props.whatIf.offsetKm === 0 ? 'Track: as planned' : `Detour ${sideKm(props.whatIf.offsetKm)}`
)
function describe(w: WhatIf) {
  const parts = []
  if (w.shiftFt)
    parts.push(`${w.shiftFt > 0 ? '+' : '−'}${Math.abs(w.shiftFt).toLocaleString()} ft`)
  if (w.offsetKm) parts.push(sideKm(w.offsetKm))
  return parts.join(', ') || 'as planned'
}
const changeLabel = computed(() => describe(props.whatIf))

// Drawing: minutes along the route left to right, as on the timeline above.
const wrapEl = ref<HTMLDivElement>()
const sideEl = ref<HTMLCanvasElement>()
const topEl = ref<HTMLCanvasElement>()
const width = ref(600)
const maxMinute = computed(() => Math.max(props.points.at(-1)?.minutesFromStart ?? 1, 1))
const x = (m: number) => padL + ((width.value - padL - padR) * m) / maxMinute.value

const rgba = (c: TurbulenceCategory, a: number) =>
  `rgba(${hexRgb(categoryColors[c]).join(',')},${a})`
/** Smooth air faint, so the rough cells stand out; no data left blank. */
const cellFill = (cat: number) =>
  cat < 0 ? undefined : cat === 0 ? rgba('Smooth', 0.16) : rgba(corridorCategories[cat]!, 0.85)

function draw() {
  const c = props.corridor
  const pts = props.points
  const p = path.value
  if (!sideEl.value || !topEl.value || pts.length < 2 || c.points < pts.length) return
  const dpr = window.devicePixelRatio || 1
  const ink = props.dark ? 'rgba(255,255,255,0.9)' : '#0f172a'
  const faint = props.dark ? 'rgba(255,255,255,0.55)' : '#64748b'
  const cursorX = props.hoverMinute === undefined ? undefined : x(props.hoverMinute)

  // Side view: altitude up, cells at each level above and below the flight.
  {
    const cv = sideEl.value
    const g = setup(cv, sideH, dpr)
    const top = Math.max(...pts.map((q) => q.altitudeFt)) + c.levelsFt.at(-1)! + 1000
    const half = levelStep.value / 2
    const y = (ft: number) => 6 + ((sideH - 18) * (top - ft)) / top
    for (let k = 0; k + 1 < pts.length; k++) {
      const x0 = x(pts[k]!.minutesFromStart)
      const x1 = x(pts[k + 1]!.minutesFromStart)
      for (let l = 0; l < c.levelsFt.length; l++) {
        const alt = pts[k]!.altitudeFt + c.levelsFt[l]!
        if (alt < 0) continue
        const fill = cellFill(
          corridorCategory(c, k, l, p[k]!.offset, props.factor, props.thresholds)
        )
        if (!fill) continue
        g.fillStyle = fill
        g.fillRect(
          x0,
          y(alt + half),
          Math.max(x1 - x0, 1) + 0.5,
          y(alt - half) - y(alt + half) + 0.5
        )
      }
    }
    if (changed.value)
      line(
        g,
        pts.map((q) => [x(q.minutesFromStart), y(q.altitudeFt)]),
        faint,
        [4, 3]
      )
    line(
      g,
      pts.map((q, k) => [x(q.minutesFromStart), y(p[k]!.altitudeFt)]),
      ink
    )
    for (const ft of [10000, 20000, 30000, 40000].filter((f) => f < top))
      label(g, flightLevel(ft), y(ft) + 4, ink)
    if (cursorX !== undefined) cursor(g, cursorX, sideH, ink)
  }

  // Top view: either side of the track, cells at the level the (what-if) flight is at.
  {
    const cv = topEl.value
    const g = setup(cv, topH, dpr)
    const span = c.offsetsKm.at(-1)! + offsetStep.value / 2
    const half = offsetStep.value / 2
    // Left of the track (negative) on top: north up for a flight heading east.
    const y = (km: number) => 4 + ((topH - 8) * (km + span)) / (2 * span)
    for (let k = 0; k + 1 < pts.length; k++) {
      const x0 = x(pts[k]!.minutesFromStart)
      const x1 = x(pts[k + 1]!.minutesFromStart)
      for (let o = 0; o < c.offsetsKm.length; o++) {
        const fill = cellFill(
          corridorCategory(c, k, p[k]!.level, o, props.factor, props.thresholds)
        )
        if (!fill) continue
        g.fillStyle = fill
        const km = c.offsetsKm[o]!
        g.fillRect(x0, y(km - half), Math.max(x1 - x0, 1) + 0.5, y(km + half) - y(km - half) + 0.5)
      }
    }
    if (changed.value)
      line(
        g,
        pts.map((q) => [x(q.minutesFromStart), y(0)]),
        faint,
        [4, 3]
      )
    line(
      g,
      pts.map((q, k) => [x(q.minutesFromStart), y(c.offsetsKm[p[k]!.offset]!)]),
      ink
    )
    label(g, `${span.toFixed(0)} km left`, 14, ink)
    label(g, `${span.toFixed(0)} km right`, topH - 6, ink)
    if (cursorX !== undefined) cursor(g, cursorX, topH, ink)
  }
}

function setup(cv: HTMLCanvasElement, h: number, dpr: number) {
  cv.width = Math.round(width.value * dpr)
  cv.height = Math.round(h * dpr)
  const g = cv.getContext('2d')!
  g.setTransform(dpr, 0, 0, dpr, 0, 0)
  g.clearRect(0, 0, width.value, h)
  return g
}

/** Axis text over the cells, outlined so it reads on any of them. */
function label(g: CanvasRenderingContext2D, text: string, y: number, ink: string) {
  g.save()
  g.font = '11px sans-serif'
  g.lineWidth = 3
  g.strokeStyle = props.dark ? 'rgba(24,39,52,0.85)' : 'rgba(255,255,255,0.9)'
  g.strokeText(text, padL + 2, y)
  g.fillStyle = ink
  g.fillText(text, padL + 2, y)
  g.restore()
}

function line(g: CanvasRenderingContext2D, xy: number[][], color: string, dash: number[] = []) {
  g.save()
  g.strokeStyle = color
  g.lineWidth = dash.length ? 1.2 : 2
  g.setLineDash(dash)
  g.beginPath()
  xy.forEach(([px, py], i) => (i ? g.lineTo(px!, py!) : g.moveTo(px!, py!)))
  g.stroke()
  g.restore()
}

function cursor(g: CanvasRenderingContext2D, cx: number, h: number, color: string) {
  g.save()
  g.strokeStyle = color
  g.lineWidth = 1
  g.beginPath()
  g.moveTo(cx, 0)
  g.lineTo(cx, h)
  g.stroke()
  g.restore()
}

const hovered = computed(() => {
  if (props.hoverMinute === undefined) return undefined
  const point = pointAt(props.points, props.hoverMinute)
  if (!point) return undefined
  const k = props.points.indexOf(point)
  const at = path.value[k]!
  const cat = corridorCategory(
    props.corridor,
    k,
    at.level,
    at.offset,
    props.factor,
    props.thresholds
  )
  return {
    point,
    path: at,
    category: (cat < 0 ? 'NoData' : corridorCategories[cat]!) as TurbulenceCategory
  }
})
const readoutStyle = computed(() => {
  const at = props.hoverMinute === undefined ? 0 : x(props.hoverMinute)
  return at < width.value / 2
    ? { left: `${at + 8}px` }
    : { left: `${at - 8}px`, transform: 'translateX(-100%)' }
})
const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

function onPointer(e: PointerEvent) {
  const rect = (e.target as HTMLElement).getBoundingClientRect()
  const m = ((e.clientX - rect.left - padL) * maxMinute.value) / (width.value - padL - padR)
  emit('hover', Math.min(Math.max(m, 0), maxMinute.value))
}
const onLeave = (e: PointerEvent) => e.pointerType === 'mouse' && emit('hover', undefined)

let observer: ResizeObserver | undefined
let frame = 0
const redraw = () => {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(draw)
}
onMounted(() => {
  observer = new ResizeObserver(([entry]) => {
    width.value = Math.max(240, Math.round(entry!.contentRect.width))
    redraw()
  })
  observer.observe(wrapEl.value!)
  redraw()
})
onBeforeUnmount(() => {
  observer?.disconnect()
  cancelAnimationFrame(frame)
})
watch(
  () => [props.corridor, props.points, props.whatIf, props.hoverMinute, props.factor, props.dark],
  redraw
)
</script>

<style scoped>
.corridor {
  font-size: 12px;
  color: #0f172a;
}
.corridor.dark {
  color: rgba(255, 255, 255, 0.92);
}
.controls {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.control {
  flex: 1 1 180px;
  min-width: 160px;
}
.label {
  font-weight: 600;
}
.summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 10px;
  margin: 2px 0 6px;
}
.good {
  color: #16a34a;
}
.bad {
  color: #dc2626;
}
.dark .good {
  color: #4ade80;
}
.dark .bad {
  color: #f87171;
}
.best {
  background: transparent;
  color: inherit;
  border: 1px solid currentColor;
  border-radius: 12px;
  padding: 1px 8px;
  font-size: 11px;
  opacity: 0.85;
}
.best:hover {
  opacity: 1;
}
.views {
  position: relative;
}
.view-label {
  opacity: 0.7;
  font-size: 11px;
  margin-top: 2px;
}
canvas {
  width: 100%;
  display: block;
  cursor: crosshair;
  touch-action: pan-y;
}
.readout {
  position: absolute;
  top: 18px;
  pointer-events: none;
  white-space: nowrap;
  background: rgba(15, 23, 42, 0.9);
  color: #fff;
  padding: 3px 8px;
  border-radius: 6px;
}
.swatch {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
}
</style>
