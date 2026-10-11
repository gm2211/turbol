<template>
  <v-bottom-navigation
    :model-value="current"
    grow
    bg-color="var(--my-blue)"
    color="white"
    class="mobile-nav"
    :height="56"
  >
    <v-btn v-for="r in navRoutes" :key="r.path" :value="r.path" :to="r.path" class="nav-btn">
      <v-icon :icon="r.mobileIcon" />
      <span>{{ r.short }}</span>
    </v-btn>
  </v-bottom-navigation>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { navRoutes } from '@/components/layout/navRoutes'

const route = useRoute()
const current = computed(() => navRoutes.find((r) => route.path.startsWith(r.path))?.path)
</script>

<style scoped>
/* Clear the home indicator on notched iPhones. */
.mobile-nav {
  padding-bottom: env(safe-area-inset-bottom);
  height: calc(56px + env(safe-area-inset-bottom)) !important;
}
.nav-btn {
  min-width: 0;
  font-size: 11px;
  text-transform: none;
  letter-spacing: 0;
}
</style>
