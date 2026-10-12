<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppBar from '@/components/layout/AppBar.vue'
import MobileNav from '@/components/layout/MobileNav.vue'
import { useMobile } from '@/util/useMobile'

const mobile = useMobile()
</script>

<template>
  <v-card>
    <v-app>
      <AppBar />
      <MobileNav v-if="mobile" />
      <v-main class="bg">
        <v-container fluid class="fill-height page-container" :class="mobile ? 'pa-1' : 'pt-1 pb-1'">
          <RouterView />
        </v-container>
      </v-main>
    </v-app>
  </v-card>
</template>
