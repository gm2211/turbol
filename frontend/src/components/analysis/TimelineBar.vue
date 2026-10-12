<template>
  <div class="timeline">
    <div class="bar">
      <div
        v-for="(s, i) in segments"
        :key="i"
        class="seg"
        :style="{
          left: `${(100 * s.startMinute) / total}%`,
          width: `${Math.max((100 * (s.endMinute - s.startMinute)) / total, 0.3)}%`,
          background: categoryColors[s.category]
        }"
        :title="`${categoryLabels[s.category]}: ${formatDuration(s.startMinute)} to ${formatDuration(s.endMinute)}`"
      />
      <div
        v-if="band"
        class="band"
        :style="{ left: `${(100 * band.from) / total}%`, width: `${(100 * (band.to - band.from)) / total}%` }"
        :title="`Within ${formatDuration(uncertaintyMinutes ?? 0)} of here 9 times in 10`"
      />
      <div
        v-if="nowMinute !== undefined"
        class="now"
        :style="{ left: `${(100 * nowMinute) / total}%` }"
      />
    </div>
    <div class="ticks">
      <span>{{ startLabel }}</span>
      <span>{{ endLabel }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { type Segment, formatDuration } from '@/api/flights'
import { categoryColors, categoryLabels } from '@/api/turbulence'

const props = defineProps<{
  segments: Segment[]
  startLabel: string
  endLabel: string
  nowMinute?: number
  /** Shades the minutes either side of `nowMinute` where the plane may really be. */
  uncertaintyMinutes?: number
}>()
const total = computed(() => Math.max(props.segments.at(-1)?.endMinute ?? 1, 1))
/** The stretch of the flight the plane may really be in, cut to the bar. */
const band = computed(() => {
  if (props.nowMinute === undefined || !props.uncertaintyMinutes) return undefined
  const from = Math.max(0, props.nowMinute - props.uncertaintyMinutes)
  const to = Math.min(total.value, props.nowMinute + props.uncertaintyMinutes)
  return to > from ? { from, to } : undefined
})
</script>

<style scoped>
.bar {
  position: relative;
  height: 18px;
  border-radius: 9px;
  overflow: hidden;
  background: var(--my-grey);
}
.seg {
  position: absolute;
  top: 0;
  bottom: 0;
}
.band {
  position: absolute;
  top: 0;
  bottom: 0;
  background: rgba(15, 23, 42, 0.28);
}
.now {
  position: absolute;
  top: -2px;
  bottom: -2px;
  width: 3px;
  background: var(--my-blue-solid);
}
.ticks {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--my-text-muted);
  margin-top: 2px;
}
</style>
