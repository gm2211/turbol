<template>
  <div class="legend">
    <div class="legend-title">{{ title }}</div>
    <div v-for="c in categories" :key="c" class="legend-row">
      <span class="swatch" :style="{ background: categoryColors[c] }" />
      {{ categoryLabels[c] }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { categoryColors, categoryLabels, type TurbulenceCategory } from '@/api/turbulence'

defineProps<{ title: string }>()
const categories: TurbulenceCategory[] = ['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme']
</script>

<style scoped>
.legend {
  background: rgba(255, 255, 255, 0.92);
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 18px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
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
.swatch {
  width: 14px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}
</style>
