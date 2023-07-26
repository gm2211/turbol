<template>
  <v-card class="fill-height my-card w-100">
    <l-map
      ref="map"
      v-model:zoom="zoom"
      v-model:center="mapCenter"
      @ready="onReady"
      :marker-zoom-animation="false"
      :useGlobalLeaflet="false"
    >
      <l-tile-layer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        layer-type="base"
        name="OpenStreet Maps"
      />
      <l-polyline :lat-lngs="flightPath" color="red" :options="{ interactive: false }" />
      <l-rotated-marker
        ref="planeMarker"
        v-if="flightPath.length > 0"
        :icon-id="MathUtils.randomInt()"
        :lat-lng="flightPath[flightPath.length - 1]"
        :options="{ interactive: false }"
        :icon-url="planeIconUrl.toString()"
        :icon-size="planeIconSize"
        :icon-anchor="planeIconAnchor"
        :rotation-angle="planeRotationAngle"
      />
    </l-map>
  </v-card>
</template>

<script setup lang="ts">
import 'leaflet/dist/leaflet.css'
import { LMap, LPolyline, LTileLayer } from '@vue-leaflet/vue-leaflet'
import { computed, ref, watch } from 'vue'
import planeIcon from '../../assets/icons/plane-for-map.png'
import GeoUtils from '@/util/geo'
import MathUtils from '@/util/math'
import LRotatedMarker from '@/components/map/LRotatedMarker.vue'
import { LatLngLiteral, PointTuple } from 'leaflet'

const props = defineProps<{
  flightPath: LatLngLiteral[]
}>()

const zoom = ref(4)
const mapCenter = ref([38.7994, -55.6731] as PointTuple)
const centerMap = () => {
  if (props.flightPath.length > 0) {
    const curPlanePosition: LatLngLiteral = props.flightPath[props.flightPath.length - 1]
    mapCenter.value = [curPlanePosition.lat, curPlanePosition.lng] as PointTuple
  }
}
const planeRotationAngle = computed(() => {
  if (props.flightPath.length > 1) {
    const prevPos: LatLngLiteral = props.flightPath[props.flightPath.length - 2]
    const curPos: LatLngLiteral = props.flightPath[props.flightPath.length - 1]
    return (
      (GeoUtils.calculateBearing(prevPos.lat, prevPos.lng, curPos.lat, curPos.lng) * 0.98) % 360
    )
  } else {
    return 0
  }
})
const planeIconUrl = ref(planeIcon)
const planeIconSize = computed(() => {
  const baseSize = 10
  const increment = 2
  const size = baseSize + increment * zoom.value
  return [Math.min(20, size), Math.min(20, size)] as PointTuple
})
const planeIconAnchor = computed(
  () => [planeIconSize.value[0] / 2, planeIconSize.value[1] / 2] as PointTuple
)
const planeMarker = ref<typeof LRotatedMarker>(null as any)
const map = ref<typeof LMap>(null as any)
const onReady = () => {
  map.value.leafletObject.on('zoomend', () => onZoomStart())
  centerMap()
}
const onZoomStart = () => {
  if (planeMarker.value) {
    planeMarker.value.applyRotation()
  }
}

watch(props.flightPath, () => {
  console.log('flightPath changed')
  centerMap()
})
</script>
