<template>
  <v-navigation-drawer
    :class="rail ? 'navbar-collapsed' : 'navbar-expanded'"
    :rail="rail"
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
          <v-avatar size="40">
            <v-img :src="route.icon" :style="`opacity: ${route.iconOpacity}`" />
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
import { ref } from 'vue'

const rail = ref(true)
const routes = [
  {
    name: 'Search',
    path: 'search',
    icon: planeIcon,
    iconOpacity: 0.58
  },
  {
    name: 'Forecast',
    path: 'forecast',
    icon: forecastIcon,
    iconOpacity: 1
  }
]
</script>
