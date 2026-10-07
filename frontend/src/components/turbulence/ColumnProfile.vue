<template>
  <div class="profile">
    <div class="profile-title">Above and below this spot</div>
    <div class="muted">{{ column.lat.toFixed(2) }}, {{ column.lon.toFixed(2) }}</div>
    <div class="summary">
      <div>
        <span>Up to {{ flightLevel(levelFt + window) }}</span>
        <b :style="{ color: categoryColors[worstAbove] }">{{ categoryLabels[worstAbove] }}</b>
      </div>
      <div>
        <span>At {{ flightLevel(levelFt) }}</span>
        <b :style="{ color: categoryColors[atLevel] }">{{ categoryLabels[atLevel] }}</b>
      </div>
      <div>
        <span>Down to {{ flightLevel(Math.max(levelFt - window, 0)) }}</span>
        <b :style="{ color: categoryColors[worstBelow] }">{{ categoryLabels[worstBelow] }}</b>
      </div>
    </div>
    <svg :viewBox="`0 0 ${width} ${height}`" :width="width" :height="height">
      <g v-for="b in bands" :key="b.levelFt">
        <rect
          :x="axisX"
          :y="y(b.top)"
          :width="barWidth"
          :height="Math.max(y(b.bottom) - y(b.top) - 0.5, 0.5)"
          :fill="
            b.level.category === 'NoData'
              ? 'rgba(200, 200, 200, 0.4)'
              : categoryColors[b.level.category]
          "
          :fill-opacity="b.level.category === 'Smooth' ? 0.35 : 0.9"
        >
          <title>
            {{ flightLevel(b.levelFt) }}: {{ categoryLabels[b.level.category]
            }}{{ b.level.edr !== undefined ? ` (EDR ${b.level.edr.toFixed(2)})` : '' }}
          </title>
        </rect>
      </g>
      <g v-for="t in ticks" :key="t">
        <line :x1="axisX - 4" :x2="axisX" :y1="y(t)" :y2="y(t)" stroke="rgba(255, 255, 255, 0.5)" />
        <text :x="axisX - 7" :y="y(t) + 3.5" text-anchor="end" class="tick">
          {{ tickLabel(t) }}
        </text>
      </g>
      <rect
        :x="axisX - 2"
        :y="y(levelFt + window)"
        :width="barWidth + 4"
        :height="y(Math.max(levelFt - window, 0)) - y(levelFt + window)"
        fill="none"
        stroke="white"
        stroke-dasharray="3 2"
      />
      <line
        :x1="axisX - 6"
        :x2="axisX + barWidth + 6"
        :y1="y(levelFt)"
        :y2="y(levelFt)"
        stroke="white"
        stroke-width="2"
      />
      <text :x="axisX + barWidth + 8" :y="y(levelFt) + 4" class="fl">
        {{ flightLevel(levelFt) }}
      </text>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  type ColumnLevel,
  type TurbulenceCategory,
  type TurbulenceColumn,
  categoryColors,
  categoryLabels,
  flightLevel
} from '@/api/turbulence'

const props = defineProps<{ column: TurbulenceColumn; levelFt: number; window: number }>()

const width = 210
const height = 300
const axisX = 52
const barWidth = 56
const maxFt = 45000
const y = (ft: number) => 8 + (1 - Math.min(ft, maxFt) / maxFt) * (height - 16)
const ticks = [0, 10000, 20000, 30000, 40000]
const tickLabel = (ft: number) =>
  ft === 0 ? 'SFC' : ft >= 18000 ? `FL${ft / 100}` : `${ft / 1000}k ft`

/** Each level drawn from halfway to the level below up to halfway to the level above. */
const bands = computed(() => {
  const levels = props.column.levels.filter((l) => l.levelFt <= maxFt)
  return levels.map((level, i) => {
    const prev = levels[i - 1]?.levelFt
    const next = levels[i + 1]?.levelFt
    const bottom = prev === undefined ? 0 : (prev + level.levelFt) / 2
    const top =
      next === undefined ? Math.min(level.levelFt + 1000, maxFt) : (next + level.levelFt) / 2
    return { levelFt: level.levelFt, level, bottom, top }
  })
})

const rank: Record<TurbulenceCategory, number> = {
  NoData: -1,
  Smooth: 0,
  Light: 1,
  Moderate: 2,
  Severe: 3,
  Extreme: 4
}
function worst(levels: ColumnLevel[]): TurbulenceCategory {
  return levels.reduce<TurbulenceCategory>(
    (w, l) => (rank[l.category] > rank[w] ? l.category : w),
    'NoData'
  )
}
const worstAbove = computed(() =>
  worst(
    props.column.levels.filter(
      (l) => l.levelFt > props.levelFt && l.levelFt <= props.levelFt + props.window
    )
  )
)
const worstBelow = computed(() =>
  worst(
    props.column.levels.filter(
      (l) => l.levelFt < props.levelFt && l.levelFt >= props.levelFt - props.window
    )
  )
)
const atLevel = computed(() => {
  const nearest = [...props.column.levels].sort(
    (a, b) => Math.abs(a.levelFt - props.levelFt) - Math.abs(b.levelFt - props.levelFt)
  )[0]
  return nearest?.category ?? 'NoData'
})
</script>

<style scoped>
.profile {
  font-size: 13px;
}
.profile-title {
  font-weight: 700;
  font-size: 15px;
}
.muted {
  color: rgba(255, 255, 255, 0.75);
}
.summary {
  margin: 8px 0;
  display: grid;
  gap: 2px;
}
.summary div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}
.summary span {
  color: rgba(255, 255, 255, 0.75);
}
.tick {
  font-size: 10px;
  fill: rgba(255, 255, 255, 0.75);
}
.fl {
  font-size: 11px;
  font-weight: 700;
  fill: white;
}
</style>
