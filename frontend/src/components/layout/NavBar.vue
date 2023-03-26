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
      <v-list-item prepend-icon="mdi-folder" title="My Files" value="myfiles"></v-list-item>
      <v-list-item prepend-icon="mdi-account-multiple" title="Shared with me" value="shared"></v-list-item>
      <v-list-item prepend-icon="mdi-star" title="Starred" value="starred"></v-list-item>
    </v-list>
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