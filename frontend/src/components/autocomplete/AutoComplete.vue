<template>
  <v-autocomplete
    :class="cssClass"
    v-model="selectedValue"
    v-model:search="selectedValueBeingEntered"
    :label="label"
    :loading="loadingCompletions"
    :items="completions"
    :item-value="selectedValueFunction"
    :item-title="makeSelectedItemTitle"
    :custom-filter="filterCompletions"
    class="mx-4"
    density="comfortable"
    hide-no-data
    hide-details
    clearable
    style="max-width: 300px; min-width: 300px;"
  >
    <template v-slot:item="{ props, item }">
      <v-list-item
        v-bind="props"
        :title="makeCompletionItemTitle(item)"
        :subtitle="makeCompletionItemSubtitle(item)"
      ></v-list-item>
    </template>
  </v-autocomplete>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'

const emit = defineEmits<{
  (e: 'needs-fetch', query: string): void
}>()

defineProps<{
  cssClass: string
  label: string
  completions: any[]
  selectedValueFunction: (item: Record<string, any>, fallback?: any) => any,
  makeSelectedItemTitle: (item: Record<string, any>, fallback?: any) => any,
  makeCompletionItemTitle: (item: any) => string
  makeCompletionItemSubtitle: (item: any) => string
  filterCompletions: (value: string, query: string, item?: any) => boolean
}>()

const loadingCompletions = ref(false)
const selectedValue = ref(undefined)
const selectedValueBeingEntered = ref(undefined as string | undefined)

watch(selectedValueBeingEntered, async (query) => {
  loadingCompletions.value = true
  emit('needs-fetch', query || '')
  loadingCompletions.value = false
})

defineExpose({ selectedValue })
</script>
