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
            <v-avatar size="32" color="#2f9be4">
              <v-icon :icon="route.mdiIcon" color="white" size="20" />
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
.live-dot {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #3ddc84;
  box-shadow: 0 0 0 1.5px white;
  animation: live-pulse 2s ease-out infinite;
}
@keyframes live-pulse {
  0% {
    box-shadow:
      0 0 0 1.5px white,
      0 0 0 1.5px rgba(61, 220, 132, 0.6);
  }
  70% {
    box-shadow:
      0 0 0 1.5px white,
      0 0 0 7px rgba(61, 220, 132, 0);
  }
  100% {
    box-shadow:
      0 0 0 1.5px white,
      0 0 0 7px rgba(61, 220, 132, 0);
  }
}
</style>
