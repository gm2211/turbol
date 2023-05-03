<template>
  <AutoComplete
    ref="genericAutocompleteComponent"
    :css-class="cssClass"
    :filter-completions="filterAirportCompletions"
    :completions="airportCompletions"
    :label="label"
    :selected-value-function="identityFunction"
    :make-selected-item-title="makeItemTitle"
    :make-completion-item-title="makeCompletionItemTitle"
    :make-completion-item-subtitle="makeCompletionItemSubtitle"
    @needs-fetch="updateAirportCompletions"
  />
</template>

<script setup lang="ts">
import { useFlightsStore } from '@/stores/flights-store'
import type { Airport } from '@/objects/airports/airports'
import StringsUtils from '@/util/strings'
import AutoComplete from '@/components/autocomplete/AutoComplete.vue'
import { computed, ref } from 'vue'
import CollectionsUtils from '@/util/collections'

defineProps({
  cssClass: {
    type: String,
    default: 'justify-center ma-lg-16'
  },
  label: {
    type: String,
    required: true
  }
})

const identityFunction = (item: Record<string, any>) => item
const flightsStore = useFlightsStore()
const airportCompletions = ref([] as Array<Airport>)

const genericAutocompleteComponent = ref(null as any)
const selectedAirport = computed(() => {
  return genericAutocompleteComponent.value.selectedValue || ({} as Airport)
})
const makeItemTitle = (item: Record<string, any>, fallback?: any) => {
  if (Object.keys(item).length == 0) {
    return fallback
  }
  return `${item['city']} (${item['iata']})`
}
const makeCompletionItemTitle = (item: any) => `${item?.raw?.city} - ${item?.raw?.name}`
const makeCompletionItemSubtitle = (item: any) => `${item?.raw?.icao} , ${item?.raw?.iata}`
const extractComparisonFields = (airport: Airport) =>
  [
    [airport.icao, 1],
    [airport.iata, 1],
    [airport.name, 2],
    [airport.city, 10],
    [airport.country, 20]
  ] as Array<[string, number]>

function scoreAirport(airport: Airport, query: string) {
  const score = extractComparisonFields(airport)
    .map(([field, discountFactor]) => {
      if (field.startsWith(query)) {
        return discountFactor
      }
      return StringsUtils.editDistance(field, query) * discountFactor
    })
    .sum()
  return score || 0
}

function getAndSortFetchedAirports(query: string) {
  const airports = flightsStore.getAirports(query)
  return CollectionsUtils.sortBy(airports, (airport) => scoreAirport(airport, query))
}

const updateAirportCompletions = (query: string) => {
  flightsStore.fetchAirports(query).then(() => {
    airportCompletions.value = getAndSortFetchedAirports(query)
  })
}
// TODO(gm2211): Instead of returning bool here, return a list of [start, end] tuples for each matched chunk so that we
//               can get highlighting - this will require modifying the editDistance function to return a list of
//               [start, end] tuples
const filterAirportCompletions = (value: string, query: string, item?: any) => {
  const airport: Airport | undefined = item?.raw as Airport | undefined
  if (airport) {
    return extractComparisonFields(airport).some(([field, _ignored]) =>
      StringsUtils.similar(query, field)
    )
  }
  return StringsUtils.similar(query, value)
}

defineExpose({ selectedAirport })
</script>