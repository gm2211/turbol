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
import {onMounted} from "vue";

const props = defineProps<{
  iconId: number,
  iconUrl: string,
  iconSize: number[],
  iconAnchor: number[],
  latLng: number[],
  rotationAngle: number
}>();

const className: string = `rotated-icon-${props.iconId}`
const applyRotation = () => {
  const element = document.querySelector(`.leaflet-marker-icon.${className}`) as any;
  if (element) {
    const iconStyle = document.createElement('style', );
    iconStyle.id="rotated-icon-style"
    const translation = element.style.transform.toString() || ""
    iconStyle.textContent = `
      .${className} {
        transform-origin: center;
        transform:
          ${translation}
          rotate(${props.rotationAngle}deg) !important;
      }
      `;
    document.querySelector(`#${iconStyle.id}`)?.remove()
    document.head.appendChild(iconStyle);
  } else {
    setTimeout(applyRotation, 10);
  }
};

onMounted(() => {
  applyRotation();
})

defineExpose({applyRotation})

</script>