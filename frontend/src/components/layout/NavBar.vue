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
            <v-img :src="route.icon" :cover="false" :style="`opacity: ${route.iconOpacity}`"/>
          </v-avatar>
        </template>
      </v-list-item>
    </v-list>
  </v-navigation-drawer>
</template>

<script setup lang="ts">
import '../../assets/style/navbar.css'
import forecastIcon from '../../assets/icons/forecast.png'
import planeIcon from '../../assets/icons/plane.png'
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
    name: 'Analyze a flight',
    path: '/analyze',
    icon: planeIcon,
    iconOpacity: 0.58
  },
  {
    name: 'Follow a flight',
    path: '/follow',
    icon: planeIcon,
    iconOpacity: 1
  },
  {
    name: '3D view',
    path: '/3d',
    icon: forecastIcon,
    iconOpacity: 0.58
  }
]
</script>