<template>
  <div class="legend glass-panel" :class="{ compact }">
    <div v-if="!compact" class="legend-title">{{ title }}</div>
    <div v-for="c in categories" :key="c" class="legend-row">
      <span class="swatch" :style="{ background: categoryColors[c] }" />
      {{ compact ? shortLabels[c] : categoryLabels[c] }}
    </div>
    <div v-if="aircraftKey" class="legend-row aircraft-key">
      <span class="ring" />
      {{ compact ? 'Ring: at plane' : 'Ring around a plane: turbulence at its own altitude' }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { categoryColors, categoryLabels, type TurbulenceCategory } from '@/api/turbulence'

defineProps<{ title: string; compact?: boolean; aircraftKey?: boolean }>()
const categories: TurbulenceCategory[] = ['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme']
// One row on a phone screen.
const shortLabels: Partial<Record<TurbulenceCategory, string>> = {
  Smooth: 'Smooth',
  Light: 'Light',
  Moderate: 'Mod',
  Severe: 'Sev',
  Extreme: 'Ext'
}
</script>

<style scoped>
.legend {
  padding: 8px 10px;
  font-size: 12px;
  line-height: 18px;
}
.legend-title {
  font-weight: 600;
  margin-bottom: 2px;
}
.legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.legend.compact {
  display: flex;
  gap: 8px;
  padding: 4px 8px;
  font-size: 11px;
}
.compact .legend-row {
  gap: 3px;
}
.aircraft-key {
  margin-top: 2px;
}
.compact .aircraft-key {
  margin-top: 0;
}
.ring {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid #f97316;
  background: rgba(249, 115, 22, 0.22);
  display: inline-block;
  flex: none;
}
.swatch {
  width: 14px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}
</style>
