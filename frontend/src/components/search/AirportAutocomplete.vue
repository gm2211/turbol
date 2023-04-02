<template>
    <AutoComplete
            ref="genericAutocompleteComponent"
            :css-class="cssClass"
            :filter-completions="filterAirportCompletions"
            :completions="airportCompletions"
            :label="label"
            :item-title-field-name="airportId"
            :item-value-field-name="airportId"
            :item-props-field-names="fieldsToDisplay"
            :make-completion-item-title="makeItemTitle"
            :make-completion-item-subtitle="makeItemSubtitle"
            @needs-fetch="updateAirportCompletions"/>
</template>

<script setup lang="ts">
import {useFlightsStore} from '@/stores/flights-store'
import type {Airport} from "@/objects/airports/airports";
import StringsUtils from "@/util/strings";
import AutoComplete from "@/components/autocomplete/AutoComplete.vue";
import {computed, ref} from "vue";
import CollectionsUtils from "@/util/collections";

defineProps({
    cssClass: {
        type: String,
        default: "justify-center ma-lg-16"
    },
    label: {
        type: String,
        required: true
    }
})

const airportId = "icao"
const fieldsToDisplay = ["icao", "name"]
const flightsStore = useFlightsStore()
const airportCompletions = ref([] as Array<Airport>)

const genericAutocompleteComponent = ref(null as any)
const selectedAirport = computed(() => {
    return genericAutocompleteComponent.value.selectedValue || {} as Airport;
})
const makeItemTitle = (item: any) => item?.raw?.city + ' - ' + item?.raw?.name
const makeItemSubtitle = (item: any) => item?.raw?.icao + ', ' + item?.raw?.iata
const extractComparisonFields = (airport: Airport) =>
    [airport.icao, airport.iata, airport.name, airport.city, airport.country]

function scoreAirport(airport: Airport, query: string) {
    const score = extractComparisonFields(airport)
        .zipWithIndex()
        .map(([field, index]) => StringsUtils.editDistance(field, query) * (index + 1))
        .sum();
    return score || 0;
}

function getAndSortFetchedAirports(query: string) {
    const airports = flightsStore.getAirports(query);
    const sortedAirports = CollectionsUtils.sortBy(
        airports,
        (airport) => scoreAirport(airport, query)
    )
    console.log(`Gotten airports and sorted: ${sortedAirports.map((airport) => airport.icao)}`)
    return sortedAirports;
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
    const airport: Airport | undefined = item?.raw as (Airport | undefined)
    if (airport) {
        return extractComparisonFields(airport).some((field) => StringsUtils.similar(query, field));
    }
    return StringsUtils.similar(query, value)
}

defineExpose({selectedAirport})
</script>
