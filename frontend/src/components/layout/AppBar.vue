<script setup lang="ts">
import '../../assets/style/base.css'
import { navRoutes } from '@/components/layout/navRoutes'
import { useMobile } from '@/util/useMobile'
import logo from '../../assets/icons/logo.png'

const mobile = useMobile()
</script>
<template>
  <v-app-bar color="var(--my-blue)" :density="mobile ? 'compact' : 'default'">
    <div class="bar-row">
      <!-- The pages sit in the header on desktop; phones use the bottom tab bar. -->
      <nav v-if="!mobile" class="tabs">
        <router-link
          v-for="r in navRoutes"
          :key="r.path"
          :to="r.path"
          class="tab"
          :class="{ active: $route.path.startsWith(r.path) }"
        >
          <v-icon :icon="r.mdiIcon" size="19" />
          <span>{{ r.name }}</span>
          <span v-if="r.live" class="live-dot" />
        </router-link>
      </nav>
      <div v-else />
      <v-avatar
        variant="text"
        rounded="0"
        :style="mobile ? 'min-height: 48px; width: 150px' : 'min-height: 64px; width: 200px'"
      >
        <a style="height: 100%; width: 100%" href="/">
          <v-img :src="logo" cover alt="Turbol" />
        </a>
      </v-avatar>
      <div />
    </div>
  </v-app-bar>
</template>

<style scoped>
/* Tabs left, logo centred: equal side columns keep the logo in the middle of the bar. */
.bar-row {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 16px;
}
.tabs {
  display: flex;
  gap: 4px;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.72);
  font-size: 14px;
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
  transition:
    background 0.2s,
    color 0.2s;
}
.tab:hover {
  color: white;
  background: rgba(255, 255, 255, 0.08);
}
.tab.active {
  color: white;
  background: rgba(255, 255, 255, 0.14);
}
/* A muted teal pulse, so "live" reads without a bright green. */
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgb(127, 199, 184);
  animation: live-pulse 2s ease-out infinite;
}
@keyframes live-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(127, 199, 184, 0.6);
  }
  70%,
  100% {
    box-shadow: 0 0 0 6px rgba(127, 199, 184, 0);
  }
}
</style>
