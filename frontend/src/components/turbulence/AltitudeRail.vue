<template>
  <div class="rail glass-panel" :class="{ compact }">
    <div class="rail-title">{{ range ? bandLabel : flightLevel(draftLevel) }}</div>
    <v-range-slider
      v-if="range"
      v-model="draftBand"
      direction="vertical"
      :min="minFt"
      :max="maxFt"
      :step="1000"
      :ticks="ticks"
      show-ticks="always"
      tick-size="3"
      hide-details
      density="compact"
      theme="dark"
      color="var(--my-accent)"
      class="rail-slider"
      @end="emit('update:band', [...draftBand] as [number, number])"
    />
    <v-slider
      v-else
      v-model="draftLevel"
      direction="vertical"
      :min="minFt"
      :max="maxFt"
      :step="1000"
      :ticks="ticks"
      show-ticks="always"
      tick-size="3"
      hide-details
      density="compact"
      theme="dark"
      color="var(--my-accent)"
      class="rail-slider"
      @end="emit('update:level', draftLevel)"
    />
    <v-btn
      v-if="range"
      size="x-small"
      variant="tonal"
      theme="dark"
      class="mt-1"
      :disabled="isAll"
      @click="showAll"
      >All</v-btn
    >
  </div>
</template>

<script setup lang="ts">
/**
 * Vertical flight-level control for the live map: one level in 2D (which turbulence layer the map shows),
 * a band of levels in 3D, where the full range shows every level.
 */
import { computed, ref, watch } from 'vue'
import { flightLevel } from '@/api/turbulence'

const props = defineProps<{
  range: boolean
  level: number
  band: [number, number]
  compact?: boolean
}>()
const emit = defineEmits<{
  'update:level': [level: number]
  'update:band': [band: [number, number]]
}>()

const minFt = 1000
const maxFt = 45000
const ticks = { 10000: 'FL100', 20000: 'FL200', 30000: 'FL300', 40000: 'FL400' }

// Dragging moves the drafts; the map only reloads when the thumb is let go.
const draftLevel = ref(props.level)
const draftBand = ref<[number, number]>([...props.band])
watch(
  () => props.level,
  (l) => (draftLevel.value = l)
)
watch(
  () => props.band,
  (b) => (draftBand.value = [...b])
)

const isAll = computed(() => draftBand.value[0] <= minFt && draftBand.value[1] >= maxFt)
const bandLabel = computed(() =>
  isAll.value
    ? 'All levels'
    : `${flightLevel(draftBand.value[0])}–${flightLevel(draftBand.value[1])}`
)

function showAll() {
  draftBand.value = [minFt, maxFt]
  emit('update:band', [minFt, maxFt])
}
</script>

<style scoped>
.rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 6px;
  width: 92px;
}
.rail-title {
  font-size: 12px;
  font-weight: 700;
  text-align: center;
  white-space: nowrap;
  margin-bottom: 4px;
}
.rail-slider {
  flex: 1;
  min-height: 0;
}
.rail-slider :deep(.v-slider-track__tick-label) {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.7);
}
.compact {
  width: 76px;
  padding: 6px 4px;
}
.compact .rail-title {
  font-size: 11px;
}
</style>
