<template>
  <v-navigation-drawer
    :class="rail ? 'navbar-collapsed' : 'navbar-expanded'"
    :rail="rail"
    :permanent="true"
    @mouseover="rail = false"
    @mouseleave="rail = true"
    class="navbar-transition my-shadow"
  >
    <v-list density="compact" nav>
      <v-list-item
        v-for="route in routes"
        :title="route.name"
        :key="route.name"
        @click="$router.push(route.path)"
        class="pl-0"
      >
        <template v-slot:prepend>
          <v-avatar size="40" variant="text">
            <v-avatar
              size="32"
              class="nav-badge"
              :class="{ active: $route.path.startsWith(route.path) }"
            >
              <v-icon :icon="route.mdiIcon" size="20" />
            </v-avatar>
            <span v-if="route.live" class="live-dot" />
          </v-avatar>
        </template>
      </v-list-item>
    </v-list>
  </v-navigation-drawer>
</template>

<script setup lang="ts">
import '../../assets/style/navbar.css'
import { navRoutes as routes } from '@/components/layout/navRoutes'
import { ref } from 'vue'

const rail = ref(true)
</script>

<style scoped>
/* Dark glass badges like the header and map panels; the current page lights up in the accent blue. */
.nav-badge {
  background: var(--my-blue);
  color: rgba(255, 255, 255, 0.75);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.14);
  transition:
    background 0.2s,
    color 0.2s;
}
.nav-badge.active {
  background: var(--my-accent);
  color: white;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.35);
}
/* A muted teal pulse, so "live" reads without a bright green. */
.live-dot {
  --live-dot: rgb(127, 199, 184);
  position: absolute;
  top: 5px;
  right: 5px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--live-dot);
  box-shadow: 0 0 0 1.5px var(--my-blue-solid);
  animation: live-pulse 2s ease-out infinite;
}
@keyframes live-pulse {
  0% {
    box-shadow:
      0 0 0 1.5px var(--my-blue-solid),
      0 0 0 1.5px rgba(127, 199, 184, 0.6);
  }
  70% {
    box-shadow:
      0 0 0 1.5px var(--my-blue-solid),
      0 0 0 7px rgba(127, 199, 184, 0);
  }
  100% {
    box-shadow:
      0 0 0 1.5px var(--my-blue-solid),
      0 0 0 7px rgba(127, 199, 184, 0);
  }
}
</style>
