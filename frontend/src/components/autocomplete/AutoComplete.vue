<template>
  <v-autocomplete
    :class="cssClass"
    v-model="selectedValue"
    v-model:search="selectedValueBeingEntered"
    :label="label"
    :loading="loadingCompletions"
    :items="completions"
    :item-title="itemTitleFieldName"
    :item-value="itemValueFieldName"
    :item-props="itemPropsFieldNames"
    :custom-filter="filterCompletions"
    class="mx-4"
    density="comfortable"
    hide-no-data
    hide-details
    clearable
    style="max-width: 300px"
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
  itemTitleFieldName: string
  itemValueFieldName: string
  itemPropsFieldNames: string[]
  makeCompletionItemTitle: (item: any) => string
  makeCompletionItemSubtitle: (item: any) => string
  filterCompletions: (value: string, query: string, item?: any) => boolean
}>()

const loadingCompletions = ref(false)
const selectedValue = ref(null)
const selectedValueBeingEntered = ref(null)

watch(selectedValueBeingEntered, async (query) => {
  loadingCompletions.value = true
  emit('needs-fetch', query || '')
  loadingCompletions.value = false
})

defineExpose({ selectedValue })
</script>
