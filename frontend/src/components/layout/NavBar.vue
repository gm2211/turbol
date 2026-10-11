<template>
  <v-navigation-drawer
      :class="rail ? 'navbar-collapsed' : 'navbar-expanded'"
      :rail="rail"
      :permanent=true
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
            <v-avatar v-if="route.mdiIcon" size="32" color="#2f9be4">
              <v-icon :icon="route.mdiIcon" color="white" size="20"/>
            </v-avatar>
            <v-img v-else :src="route.icon" :cover="false" :style="`opacity: ${route.iconOpacity}`"/>
          </v-avatar>
        </template>
      </v-list-item>
    </v-list>
  </v-navigation-drawer>
</template>

<script setup lang="ts">
import '../../assets/style/navbar.css'
import forecastIcon from '../../assets/icons/forecast.png'
import {ref} from 'vue'

const rail = ref(true)
const routes = [
  {
    name: 'Live map',
    path: '/map',
    icon: forecastIcon,
    iconOpacity: 1
  },
  {
    // Each page gets its own icon so the collapsed bar can be read at a glance.
    name: 'Analyze a flight',
    path: '/analyze',
    mdiIcon: 'mdi-airplane-search'
  },
  {
    name: 'Follow a flight',
    path: '/follow',
    mdiIcon: 'mdi-crosshairs-gps'
  },
  {
    name: '3D view',
    path: '/3d',
    mdiIcon: 'mdi-cube-outline'
  }
]
</script>