<script setup lang="ts">
import "../../assets/style/main.css"</script>

<template>
  <v-navigation-drawer
      :rail="rail"
      @mouseover="rail = false"
      @mouseleave="rail = true"
      style="box-shadow: 0px 2px 5px #000000;border: none;"
      color="var(--my-light-grey)">
  </v-navigation-drawer>
</template>

<script lang="ts">
export default {
  data() {
    return {
      rail: true
    }
  }
}
</script>