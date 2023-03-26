<script setup lang="ts">
import "../../assets/style/base.css"</script>
<template>
  <v-app-bar color="var(--my-blue)">
    <v-row no-gutters>
      <v-col/>
      <v-col class="v-col-md-auto mx-auto">
        <v-avatar style="min-height: 64px;width: 200px">
          <v-img :src="image" cover alt="Turbol"/>
        </v-avatar>
      </v-col>
      <v-col/>
    </v-row>
  </v-app-bar>
</template>

<script lang="ts">
import image from "../../assets/icons/logo.png"

export default {
  data() {
    return {
      image: image
    }
  }
}
</script>
