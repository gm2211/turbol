<template>
    <v-row class="justify-center ma-lg-16">
        <v-autocomplete
                :v-model="selectedValue"
                :v-model:search="selectedValueBeingEntered"
                :loading="loadingCompletions"
                :items="completions"
                item-title="icao"
                item-value="icao"
                item-props="['icao', 'name']"
                :custom-filter="filterCompletions"
                class="mx-4"
                density="comfortable"
                hide-no-data
                hide-details
                label="Source Airport:"
                style="max-width: 300px;"
        >
            <template v-slot:item="{ props, item }">
                <v-list-item
                        v-bind="props"
                        :title="item?.raw?.city + ' - ' + item?.raw?.name"
                        :subtitle="item?.raw?.icao + ', ' + item?.raw?.iata"
                ></v-list-item>
            </template>
        </v-autocomplete>
    </v-row>
</template>

<script setup lang="ts">
import {defineEmits, defineProps, ref, watch} from 'vue'


const emit = defineEmits<{
    (e: "needs-fetch", query: string): void
}>()
const props = defineProps<{
    completions: Array<any>
    selectedValue: string
    selectedValueBeingEntered: string,
    filterCompletions: (value: string, query: string, item?: any) => boolean
}>()

const loadingCompletions = ref(false)

watch(() => props.selectedValueBeingEntered, async (query) => {
    loadingCompletions.value = true
    emit("needs-fetch", query)
    loadingCompletions.value = false
})
</script>
