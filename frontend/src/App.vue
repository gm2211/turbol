<script setup lang="ts">
import {RouterView} from 'vue-router'
import AppBar from "@/components/layout/AppBar.vue";
import NavBar from "@/components/layout/NavBar.vue";</script>

<template>
  <v-card>
    <v-app>
      <AppBar/>
      <NavBar/>
      <v-main class="bg">
        <v-container fluid class="fill-height pt-1 pb-1">
          <RouterView/>
        </v-container>
      </v-main>
    </v-app>
  </v-card>
</template>