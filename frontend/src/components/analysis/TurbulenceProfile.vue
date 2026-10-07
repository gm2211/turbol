<template>
  <svg ref="svgEl" :viewBox="`0 0 ${W} ${H}`" class="profile">
    <!-- threshold lines -->
    <g v-for="t in thresholdLines" :key="t.label">
      <line :x1="padL" :x2="W - padR" :y1="y(t.value)" :y2="y(t.value)" :stroke="t.color" stroke-dasharray="4 4" />
      <text :x="W - padR - 4" :y="y(t.value) - 3" text-anchor="end" class="lbl" :fill="t.color">{{ t.label }}</text>
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
    <!-- altitude -->
    <polyline :points="altitudeLine" fill="none" stroke="var(--my-blue-solid)" stroke-width="1.5" opacity="0.6" />
    <line :x1="padL" :x2="W - padR" :y1="H - padB" :y2="H - padB" stroke="#94a3b8" />
    <text :x="padL" :y="H - 4" class="lbl">0</text>
    <text :x="W - padR" :y="H - 4" text-anchor="end" class="lbl">{{ formatDuration(maxMinute) }}</text>
    <text :x="padL + 4" :y="12" class="lbl">EDR (bars) · altitude (line, max {{ maxAltLabel }})</text>
  </svg>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { type RoutePoint, formatDuration } from '@/api/flights'
import { categoryColors, flightLevel } from '@/api/turbulence'

const props = defineProps<{ points: RoutePoint[]; light: number; moderate: number }>()
// Viewbox width follows the rendered width so labels keep their proportions.
const svgEl = ref<SVGSVGElement>()
const W = ref(800)
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(([entry]) => (W.value = Math.max(300, Math.round(entry.contentRect.width))))
  observer.observe(svgEl.value!)
})
onBeforeUnmount(() => observer?.disconnect())
const H = 200
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
const y = (edr: number) => H - padB - ((H - padB - padT) * Math.min(edr, maxEdr.value)) / maxEdr.value
const altitudeLine = computed(() =>
  props.points
    .map((p) => `${x(p.minutesFromStart)},${H - padB - ((H - padB - padT) * p.altitudeFt) / maxAlt.value}`)
    .join(' ')
)
const thresholdLines = computed(() => [
  { label: 'light', value: props.light, color: categoryColors.Light },
  { label: 'moderate', value: props.moderate, color: categoryColors.Moderate }
])
</script>

<style scoped>
.profile {
  width: 100%;
  height: 200px;
}
.lbl {
  font-size: 11px;
  fill: var(--my-text-muted);
}
</style>
