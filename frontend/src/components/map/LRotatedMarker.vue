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

let counter: number = 0
const className: string = `rotated-icon-${props.iconId}`
const genStyleId = (cnt: number) => `${className}-${cnt}-style`
const applyRotation = () => {
  const element = document.querySelector(`.leaflet-marker-icon.${className}`) as any;
  if (element) {
    const iconStyle = document.createElement('style',);
    iconStyle.id = genStyleId(++counter)
    const translation = element.style.transform.toString() || ""
    iconStyle.textContent = `
      .${className} {
        transform-origin: center;
        transform:
          ${translation}
          rotate(${props.rotationAngle}deg) !important;
      }
      `;
    document.head.appendChild(iconStyle);
    // Delete prev style
    document.querySelector(`#${genStyleId(counter - 1)}`)?.remove()
  } else {
    setTimeout(applyRotation, 1);
  }
};

onMounted(() => {
  applyRotation();
})

defineExpose({applyRotation})

</script>