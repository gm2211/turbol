<template>
  <l-marker :lat-lng="latLng" :options="{ interactive: false }">
    <l-icon
      :icon-url="iconUrl"
      :icon-size="new Point(iconSize[0], iconSize[1])"
      :icon-anchor="new Point(iconAnchor[0], iconAnchor[1])"
      :class-name="className"
    />
  </l-marker>
</template>

<script setup lang="ts">
import { LIcon, LMarker } from '@vue-leaflet/vue-leaflet'
import { onMounted } from 'vue'
import { LatLng, Point } from 'leaflet'

const props = defineProps<{
  iconId: number
  iconUrl: string
  iconSize: number[]
  iconAnchor: number[]
  latLng: LatLng
  rotationAngle: number
}>()

// Stuff related to applying rotation to marker
const className: string = `rotated-icon-${props.iconId}`
let styleApplicationCounter: number = 0
const genStyleId = (cnt: number) => `${className}-${cnt}-style`
const applyRotation = () => {
  const element = document.querySelector(`.leaflet-marker-icon.${className}`) as any
  if (element) {
    const iconStyle = document.createElement('style')
    iconStyle.id = genStyleId(++styleApplicationCounter)
    const translation = element.style.transform.toString() || ''
    iconStyle.textContent = `
      .${className} {
        transform-origin: center;
        transform:
          ${translation}
          rotate(${props.rotationAngle}deg) !important;
      }
      `
    document.head.appendChild(iconStyle)
    // Delete prev style
    document.querySelector(`#${genStyleId(styleApplicationCounter - 1)}`)?.remove()
  } else {
    setTimeout(applyRotation, 1)
  }
}

onMounted(() => {
  applyRotation()
})

defineExpose({ applyRotation })
</script>