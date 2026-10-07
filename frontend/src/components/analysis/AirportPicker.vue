<template>
  <v-autocomplete
    v-model="selected"
    :items="items"
    :loading="loading"
    :label="label"
    item-title="title"
    return-object
    no-filter
    hide-no-data
    density="comfortable"
    variant="outlined"
    clearable
    @update:search="onSearch"
  />
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { type Place, searchAirports } from '@/api/flights'

defineProps<{ label: string }>()
const model = defineModel<Place | undefined>()

interface Item {
  title: string
  place: Place
}
const items = ref<Item[]>([])
const selected = ref<Item>()
const loading = ref(false)
let timer: number | undefined

watch(selected, (item) => (model.value = item?.place))
watch(model, (place) => {
  if (place && selected.value?.place !== place) {
    const item = { title: `${place.code} · ${place.name}`, place }
    items.value = [item]
    selected.value = item
  }
})

function onSearch(query: string) {
  window.clearTimeout(timer)
  if (!query || query.length < 2 || query === selected.value?.title) return
  timer = window.setTimeout(async () => {
    loading.value = true
    try {
      const airports = await searchAirports(query)
      items.value = airports.map((a) => {
        const code = a.iata || a.icao
        return {
          title: `${code} · ${a.city} · ${a.name}`,
          place: { code, name: a.name, lat: a.location.lat, lon: a.location.lon }
        }
      })
    } finally {
      loading.value = false
    }
  }, 250)
}
</script>
