<template>
  <v-col class="fill-height">
    <v-card class="fill-height my-card w-100">
      <l-map v-model:zoom="zoom" v-model:center="mapCenter" :useGlobalLeaflet="false">
        <l-tile-layer
            url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            layer-type="base"
            name="OpenStreet Maps"/>
        <l-polyline :lat-lngs="flightPath" color="red" :options="{ interactive: false }"/>
        <l-rotated-marker
            ref="planeMarker"
            v-if="flightPath.length > 0"
            :icon-id="MathUtils.randomInt()"
            :lat-lng="flightPath[flightPath.length - 1]"
            :options="{ interactive: false }"
            :icon-url="planeIconUrl.toString()"
            :icon-size="planeIconSize"
            :icon-anchor="planeIconAnchor"
            :rotation-angle="planeRotationAngle"/>
      </l-map>
    </v-card>
  </v-col>
</template>

<script setup lang="ts">
import "leaflet/dist/leaflet.css"
import {LMap, LPolyline, LTileLayer} from "@vue-leaflet/vue-leaflet"
import {computed, ref, watch} from "vue";
import planeIcon from '../../assets/icons/plane-for-map.png'
import GeoUtils from '@/util/geo'
import MathUtils from "@/util/math";
import LRotatedMarker from "@/components/map/LRotatedMarker.vue";

const zoom = ref(4)
const flightPath = ref([[38.7994, -55.6731], [40, -56], [41, -59], [41, -90], [29, -80]])
const mapCenter = ref([38.7994, -55.6731]);
const centerMap = () => {
  if (flightPath.value.length > 0) {
    mapCenter.value = flightPath.value[flightPath.value.length - 1];
  }
}
const planeRotationAngle = computed(() => {
  if (flightPath.value.length > 1) {
    const [startLat, startLng] = flightPath.value[flightPath.value.length - 2];
    const [destLat, destLng] = flightPath.value[flightPath.value.length - 1];
    return GeoUtils.calculateBearing(startLat, startLng, destLat, destLng) * 0.98 % 360;
  } else {
    return 0;
  }
});
const planeIconUrl = ref(planeIcon);
const planeIconSize = computed(() => {
  const baseSize = 10;
  const increment = 2;
  const size = baseSize + increment * zoom.value;
  return [Math.min(20, size), Math.min(20, size)];
});
const planeIconAnchor = computed(() => [planeIconSize.value[0] / 2, planeIconSize.value[1] / 2])
const planeMarker = ref<LRotatedMarker>(undefined as LRotatedMarker);

watch(flightPath, () => centerMap())
watch(zoom, () => planeMarker.value.applyRotation())
centerMap()
</script>
