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
      <div v-if="nowMinute !== undefined" class="now" :style="{ left: `${(100 * nowMinute) / total}%` }" />
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
}>()
const total = computed(() => Math.max(props.segments.at(-1)?.endMinute ?? 1, 1))
</script>

<style scoped>
.bar {
  position: relative;
  height: 18px;
  border-radius: 9px;
  overflow: hidden;
  background: #e2e8f0;
}
.seg {
  position: absolute;
  top: 0;
  bottom: 0;
}
.now {
  position: absolute;
  top: -2px;
  bottom: -2px;
  width: 3px;
  background: #0f172a;
}
.ticks {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #475569;
  margin-top: 2px;
}
</style>
