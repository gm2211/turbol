<template>
  <div class="add-flight">
    <div class="eyebrow">Save a flight for offline</div>
    <p class="muted intro">
      Do this before boarding, while you have a connection. Turbol downloads the turbulence along
      your flight so it keeps working in airplane mode and on slow plane Wi-Fi.
    </p>
    <div class="form-row">
      <v-btn-toggle
        v-model="mode"
        mandatory
        density="compact"
        variant="outlined"
        divided
        class="mode"
      >
        <v-btn value="flight">Flight</v-btn>
        <v-btn value="route">Route</v-btn>
      </v-btn-toggle>
      <v-text-field
        v-if="mode === 'flight'"
        v-model="flightQuery"
        class="grow"
        label="Flight number (UA1517)"
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="save"
      />
      <template v-else>
        <AirportPicker v-model="origin" class="grow" label="From" />
        <AirportPicker v-model="destination" class="grow" label="To" />
      </template>
      <v-text-field
        v-model="departureLocal"
        class="departure"
        type="datetime-local"
        label="Departure (local time)"
        density="compact"
        variant="outlined"
        hide-details
      />
      <v-btn
        color="#10324f"
        :loading="saving"
        height="40"
        prepend-icon="mdi-download"
        @click="save"
      >
        Save for offline
      </v-btn>
      <v-btn v-if="canCancel" variant="text" height="40" @click="emit('cancel')">Cancel</v-btn>
    </div>
    <v-alert v-if="error" type="warning" variant="tonal" class="mt-2" density="compact">{{
      error
    }}</v-alert>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AirportPicker from '@/components/analysis/AirportPicker.vue'
import { type Place, analyzeFlight, lookupFlight } from '@/api/flights'
import { idbStore } from '@/fly/db'
import { type SavedFlight, newFlight, saveFlight } from '@/fly/flights'

defineProps<{ canCancel?: boolean }>()
const emit = defineEmits<{ saved: [flight: SavedFlight]; cancel: [] }>()

const mode = ref<'flight' | 'route'>('flight')
const flightQuery = ref('')
const origin = ref<Place>()
const destination = ref<Place>()
const departureLocal = ref(toLocalInput(new Date(Date.now() + 2 * 3600_000)))
const saving = ref(false)
const error = ref<string>()

function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function save() {
  error.value = undefined
  if (!navigator.onLine) {
    error.value = 'Saving a flight needs a connection: try again before you board.'
    return
  }
  saving.value = true
  try {
    let from = origin.value
    let to = destination.value
    let label: string
    let callsign: string | undefined
    if (mode.value === 'flight') {
      const q = flightQuery.value.trim()
      if (!q) return
      const found = await lookupFlight(q)
      if (!found.route) {
        error.value = `No route found for ${q}. Try the airline code plus number (UA1517) or the Route tab.`
        return
      }
      from = found.route.origin
      to = found.route.destination
      callsign = found.callsign
      label = found.route.iataCallsign ?? found.route.callsign
    } else {
      if (!from || !to) {
        error.value = 'Pick both airports.'
        return
      }
      label = `${from.code} → ${to.code}`
    }
    const departure = new Date(departureLocal.value).getTime()
    // The server knows the aircraft (for its turbulence thresholds) and the cruise altitude it would assume.
    const server = await analyzeFlight({
      origin: from!,
      destination: to!,
      departure: new Date(departure).toISOString(),
      callsign
    })
    const flight = newFlight({
      label,
      callsign,
      origin: from!,
      destination: to!,
      departure,
      aircraftClass: server.aircraftClass,
      aircraftType: server.aircraftType,
      cruiseFt: server.cruiseAltitudeFt
    })
    emit('saved', await saveFlight(idbStore, flight))
  } catch (e) {
    error.value = `Couldn't save the flight: ${e}`
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.intro {
  font-size: 14px;
  margin: 4px 0 12px;
}
.form-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.form-row .grow {
  flex: 1 1 220px;
}
.form-row .departure {
  flex: 0 0 230px;
}
.mode {
  height: 40px;
}
.eyebrow {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #334155;
}
.muted {
  color: #475569;
}
</style>
