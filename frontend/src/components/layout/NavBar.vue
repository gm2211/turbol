<script setup lang="ts">
import "../../assets/style/navbar.css"</script>

<template>
  <v-navigation-drawer
      :class="rail ? 'navbar-collapsed' : 'navbar-expanded'"
      :rail="rail"
      @mouseover="rail = false"
      @mouseleave="rail = true"
      class="navbar-transition my-shadow"
  >
    <v-list density="compact" nav>
      <v-list-item :prepend-icon="logo" title="Forecast"> </v-list-item>
    </v-list>
  </v-navigation-drawer>
</template>

<script lang="ts">
import logo from "../../assets/icons/logo.png";
export default {

  data() {
    return {
      rail: true,
      logo: logo
    }
  }
}
</script>