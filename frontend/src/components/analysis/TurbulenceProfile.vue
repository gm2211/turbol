<template>
  <div class="profile-wrap">
    <svg
      ref="svgEl"
      :viewBox="`0 0 ${W} ${H}`"
      class="profile"
      :style="{ height: `${H}px` }"
      @pointerdown="onPointer"
      @pointermove="onPointer"
      @pointerleave="onLeave"
    >
      <!-- threshold lines -->
      <g v-for="t in thresholdLines" :key="t.label">
        <line
          :x1="padL"
          :x2="W - padR"
          :y1="y(t.value)"
          :y2="y(t.value)"
          :stroke="t.color"
          stroke-dasharray="4 4"
        />
        <text
          :x="t.label === 'light' ? padL + 4 : W - padR - 4"
          :y="y(t.value) - 3"
          :text-anchor="t.label === 'light' ? 'start' : 'end'"
          class="lbl"
          :fill="t.color"
        >
          {{ t.label }}
        </text>
      </g>
      <!-- EDR bars -->
      <rect
        v-for="(p, i) in points"
        :key="i"
        :x="x(p.minutesFromStart) - barW / 2"
        :y="y(p.edr ?? 0)"
        :width="barW"
        :height="Math.max(H - padB - y(p.edr ?? 0), 0)"
        :fill="categoryColors[p.category]"
        :opacity="p.nearGround ? 0.35 : 0.9"
      />
      <!-- where the plane is now, and where it may really be -->
      <template v-if="nowMinute !== undefined">
        <rect
          v-if="band"
          :x="band.x"
          :y="padT"
          :width="band.width"
          :height="H - padB - padT"
          fill="#0f172a"
          opacity="0.1"
        />
        <line
          :x1="x(nowMinute)"
          :x2="x(nowMinute)"
          :y1="padT"
          :y2="H - padB"
          stroke="var(--my-blue-solid)"
          stroke-width="2"
        />
      </template>
      <!-- altitude -->
      <polyline
        :points="altitudeLine"
        fill="none"
        stroke="var(--my-blue-solid)"
        stroke-width="1.5"
        opacity="0.6"
      />
      <line :x1="padL" :x2="W - padR" :y1="H - padB" :y2="H - padB" stroke="#94a3b8" />
      <text :x="padL" :y="H - 4" class="lbl">0</text>
      <text :x="W - padR" :y="H - 4" text-anchor="end" class="lbl">
        {{ formatDuration(maxMinute) }}
      </text>
      <text :x="padL + 4" :y="12" class="lbl">
        EDR (bars) · altitude (line, max {{ maxAltLabel }})
      </text>
      <!-- the point being read -->
      <g v-if="hovered">
        <line
          :x1="x(hovered.minutesFromStart)"
          :x2="x(hovered.minutesFromStart)"
          :y1="padT"
          :y2="H - padB"
          stroke="#0f172a"
          stroke-width="1"
        />
        <circle
          :cx="x(hovered.minutesFromStart)"
          :cy="altY(hovered.altitudeFt)"
          r="3.5"
          fill="var(--my-blue-solid)"
          stroke="#fff"
        />
      </g>
    </svg>
    <div v-if="hovered" class="readout" :style="readoutStyle">
      <div>
        <b>{{ formatDuration(hovered.minutesFromStart) }}</b> in · {{ clock(hovered.time) }} ·
        {{ formatDuration(maxMinute - hovered.minutesFromStart) }} left
      </div>
      <div>
        <span class="swatch" :style="{ background: categoryColors[hovered.category] }" />
        {{ categoryLabels[hovered.category] }} · EDR
        {{ hovered.edr !== undefined ? hovered.edr.toFixed(3) : 'no data' }} ·
        {{ flightLevel(hovered.altitudeFt)
        }}<template v-if="hovered.nearGround"> · near the ground</template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { type RoutePoint, formatDuration, pointAt } from '@/api/flights'
import { categoryColors, categoryLabels, flightLevel } from '@/api/turbulence'

const props = withDefaults(
  defineProps<{
    points: RoutePoint[]
    light: number
    moderate: number
    height?: number
    /** Marks where the plane is now, in minutes from the start. */
    nowMinute?: number
    /** Shades the minutes either side of `nowMinute` where the plane may really be. */
    uncertaintyMinutes?: number
    /** The minute being read, shared with the timeline bar and the map. */
    hoverMinute?: number
  }>(),
  { height: 200 }
)
const emit = defineEmits<{ hover: [minute: number | undefined] }>()
// Viewbox width follows the rendered width so labels keep their proportions.
const svgEl = ref<SVGSVGElement>()
const W = ref(800)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(
    ([entry]) => (W.value = Math.max(300, Math.round(entry.contentRect.width)))
  )
  observer.observe(svgEl.value!)
})
onBeforeUnmount(() => observer?.disconnect())
const H = props.height
const padL = 8
const padR = 8
const padB = 18
const padT = 18

const maxMinute = computed(() => Math.max(props.points.at(-1)?.minutesFromStart ?? 1, 1))
const maxEdr = computed(() => Math.max(0.25, ...props.points.map((p) => (p.edr ?? 0) * 1.15)))
const maxAlt = computed(() => Math.max(1, ...props.points.map((p) => p.altitudeFt)))
const maxAltLabel = computed(() => flightLevel(maxAlt.value))
const barW = computed(() => Math.max(1, (W.value - padL - padR) / Math.max(props.points.length, 1)))

const x = (m: number) => padL + ((W.value - padL - padR) * m) / maxMinute.value
/** The stretch of the flight the plane may really be in, cut to the chart. */
const band = computed(() => {
  if (props.nowMinute === undefined || !props.uncertaintyMinutes) return undefined
  const a = Math.max(0, props.nowMinute - props.uncertaintyMinutes)
  const b = Math.min(maxMinute.value, props.nowMinute + props.uncertaintyMinutes)
  return b > a ? { x: x(a), width: x(b) - x(a) } : undefined
})
const y = (edr: number) =>
  H - padB - ((H - padB - padT) * Math.min(edr, maxEdr.value)) / maxEdr.value
const altY = (ft: number) => H - padB - ((H - padB - padT) * ft) / maxAlt.value
const altitudeLine = computed(() =>
  props.points.map((p) => `${x(p.minutesFromStart)},${altY(p.altitudeFt)}`).join(' ')
)

// Hover or drag (mouse, pen or finger) to read the EDR, category and time at any point of the flight.
const hovered = computed(() =>
  props.hoverMinute === undefined ? undefined : pointAt(props.points, props.hoverMinute)
)
function onPointer(e: PointerEvent) {
  const rect = svgEl.value!.getBoundingClientRect()
  const px = ((e.clientX - rect.left) * W.value) / Math.max(rect.width, 1)
  const m = ((px - padL) * maxMinute.value) / (W.value - padL - padR)
  emit('hover', Math.min(Math.max(m, 0), maxMinute.value))
}
// A finger lifting off leaves the reading in place; a mouse leaving clears it.
const onLeave = (e: PointerEvent) => e.pointerType === 'mouse' && emit('hover', undefined)
/** The readout sits beside the cursor, on whichever side has room, so it never hides the point. */
const readoutStyle = computed(() => {
  const at = hovered.value ? x(hovered.value.minutesFromStart) : 0
  return at < W.value / 2
    ? { left: `${at + 8}px` }
    : { left: `${at - 8}px`, transform: 'translateX(-100%)' }
})
const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
const thresholdLines = computed(() => [
  { label: 'light', value: props.light, color: categoryColors.Light },
  { label: 'moderate', value: props.moderate, color: categoryColors.Moderate }
])
</script>

<style scoped>
.profile-wrap {
  position: relative;
}
.profile {
  width: 100%;
  display: block;
  cursor: crosshair;
  /* Horizontal drags read the chart; vertical swipes still scroll the page. */
  touch-action: pan-y;
}
.readout {
  position: absolute;
  top: 20px;
  pointer-events: none;
  white-space: nowrap;
  background: rgba(15, 23, 42, 0.9);
  color: #fff;
  font-size: 12px;
  line-height: 1.5;
  padding: 3px 8px;
  border-radius: 6px;
}
.swatch {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 2px;
  margin-right: 2px;
}
.lbl {
  font-size: 12px;
  fill: #334155;
}
</style>
