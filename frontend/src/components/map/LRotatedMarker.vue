<template>
  <l-marker :lat-lng="latLng" :options="{ interactive: false }">
    <l-icon
        :icon-url="iconUrl"
        :icon-size="iconSize"
        :icon-anchor="iconAnchor"
        :class-name="className"/>
  </l-marker>
</template>

<script setup lang="ts">
import {LIcon, LMarker} from "@vue-leaflet/vue-leaflet";

const props = defineProps<{
  iconId: number,
  iconUrl: string,
  iconSize: number[],
  iconAnchor: number[],
  latLng: number[],
  rotationAngle: number
}>();

const iconStyle = document.createElement('style');
const className = `rotated-icon-${props.iconId}`

iconStyle.textContent = `
    .${className} img {
      transform: rotate(${props.rotationAngle}deg);
      transition: transform 0.5s ease;
    }
  `;

document.head.appendChild(iconStyle);

</script>