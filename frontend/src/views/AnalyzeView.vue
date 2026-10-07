<template>
  <div class="analyze-page">
    <v-card class="glass pa-4 mb-3">
      <v-tabs v-model="mode" density="compact" class="mb-3">
        <v-tab value="flight">Flight number</v-tab>
        <v-tab value="route">Route</v-tab>
      </v-tabs>
      <v-row density="compact" align="center">
        <template v-if="mode === 'flight'">
          <v-col cols="12" md="4">
            <v-text-field
              v-model="flightQuery"
              label="Flight number or callsign (UA1517, DAL2105)"
              density="comfortable"
              variant="outlined"
              hide-details
              @keyup.enter="analyze"
            />
          </v-col>
        </template>
        <template v-else>
          <v-col cols="12" md="3"><AirportPicker v-model="origin" label="From" /></v-col>
          <v-col cols="12" md="3"><AirportPicker v-model="destination" label="To" /></v-col>
        </template>
        <v-col cols="6" md="2">
          <v-text-field
            v-model="departureLocal"
            type="datetime-local"
            label="Departure (local time)"
            density="comfortable"
            variant="outlined"
            hide-details
          />
        </v-col>
        <v-col cols="6" md="2">
          <v-select
            v-model="aircraftClass"
            :items="['Light', 'Medium', 'Heavy']"
            label="Aircraft size"
            density="comfortable"
            variant="outlined"
            hide-details
          />
        </v-col>
        <v-col cols="12" md="1">
          <v-btn color="#10324f" size="large" :loading="loading" block @click="analyze">Analyze</v-btn>
        </v-col>
      </v-row>
      <div v-if="lookup?.route" class="mt-3 text-body-2">
        <b>{{ lookup.route.iataCallsign ?? lookup.route.callsign }}</b>
        {{ lookup.route.airline ? `· ${lookup.route.airline}` : '' }} ·
        {{ lookup.route.origin.code }} → {{ lookup.route.destination.code }}
        <span v-if="lookup.live">
          · <b class="text-green-darken-2">in the air now</b> at {{ flightLevel(lookup.live.altitudeFt) }}
          <v-btn size="small" color="green" variant="tonal" class="ml-2" :to="`/follow/${lookup.live.hex}`">
            Follow live
          </v-btn>
        </span>
        <v-btn size="small" color="#10324f" variant="tonal" class="ml-2" :to="`/3d?q=${encodeURIComponent(flightQuery.trim())}`">
          View in 3D
        </v-btn>
      </div>
      <v-alert v-if="error" type="warning" variant="tonal" class="mt-3" density="compact">{{ error }}</v-alert>
    </v-card>

    <template v-if="analysis">
      <v-row density="compact">
        <v-col cols="12" md="4">
          <v-card class="glass pa-4 fill-height">
            <div class="text-overline">Forecast verdict</div>
            <v-chip :color="categoryColors[analysis.summary.worstCategory]" variant="flat" class="mb-2" label>
              Worst: {{ categoryLabels[analysis.summary.worstCategory] }}
            </v-chip>
            <div class="text-h6 mb-3">{{ analysis.summary.verdict }}</div>
            <div class="stats">
              <div><span>Distance</span>{{ Math.round(analysis.totalDistanceKm).toLocaleString() }} km</div>
              <div><span>Flight time</span>{{ formatDuration(analysis.durationMinutes) }}</div>
              <div><span>Cruise</span>{{ flightLevel(analysis.cruiseAltitudeFt) }}</div>
              <div>
                <span>Max EDR</span>{{ analysis.summary.maxEdr?.toFixed(3) ?? '–' }}
                <template v-if="analysis.summary.worstMinute !== undefined">
                  at {{ formatDuration(analysis.summary.worstMinute) }}
                </template>
              </div>
            </div>
            <div class="mt-3">
              <div v-for="c in shownCategories" :key="c" class="cat-row">
                <span class="swatch" :style="{ background: categoryColors[c] }" />
                <span class="cat-name">{{ categoryLabels[c] }}</span>
                <span>{{ formatDuration(analysis.summary.minutesByCategory[c] ?? 0) }}</span>
                <span class="muted">({{ analysis.summary.percentByCategory[c] ?? 0 }}%)</span>
              </div>
            </div>
            <v-alert
              v-for="n in analysis.summary.notes"
              :key="n"
              type="info"
              variant="tonal"
              density="compact"
              class="mt-2"
              >{{ n }}</v-alert
            >
            <div class="muted text-caption mt-3">
              GTG-N nowcast {{ fmt(analysis.dataSources.nowcastValid) }} · GTG forecast issued
              {{ fmt(analysis.dataSources.forecastIssued) }}, valid until
              {{ fmt(analysis.dataSources.forecastUntil) }}
            </div>
          </v-card>
        </v-col>
        <v-col cols="12" md="8">
          <v-card class="glass pa-2 map-card">
            <RouteMap
              :points="analysis.points"
              :origin="analyzedOrigin"
              :destination="analyzedDestination"
              :worst="worstPoint"
              :aircraft="lookup?.live"
            />
          </v-card>
        </v-col>
      </v-row>
      <v-card class="glass pa-4 mt-3">
        <div class="text-subtitle-2 mb-2">Turbulence timeline</div>
        <TimelineBar
          :segments="analysis.segments"
          :start-label="`Departure ${localTime(analysis.start)}`"
          :end-label="`Arrival ${localTime(arrival)}`"
        />
        <TurbulenceProfile
          class="mt-3"
          :points="analysis.points"
          :light="thresholds.light"
          :moderate="thresholds.moderate"
        />
      </v-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AirportPicker from '@/components/analysis/AirportPicker.vue'
import RouteMap from '@/components/analysis/RouteMap.vue'
import TimelineBar from '@/components/analysis/TimelineBar.vue'
import TurbulenceProfile from '@/components/analysis/TurbulenceProfile.vue'
import {
  type FlightLookup,
  type Place,
  type RouteAnalysis,
  analyzeFlight,
  formatDuration,
  lookupFlight,
  searchAirports
} from '@/api/flights'
import {
  type AircraftClass,
  type Thresholds,
  type TurbulenceCategory,
  categoryColors,
  categoryLabels,
  fetchStatus,
  flightLevel,
  formatUtc
} from '@/api/turbulence'

const route = useRoute()
const router = useRouter()

const mode = ref<'flight' | 'route'>('flight')
const flightQuery = ref('')
const origin = ref<Place>()
const destination = ref<Place>()
const aircraftClass = ref<AircraftClass>('Medium')
const departureLocal = ref(toLocalInput(new Date()))
const loading = ref(false)
const error = ref<string>()
const lookup = ref<FlightLookup>()
const analysis = ref<RouteAnalysis>()
const analyzedOrigin = ref<Place>()
const analyzedDestination = ref<Place>()
const allThresholds = ref<Record<AircraftClass, Thresholds>>()

const thresholds = computed(
  () => allThresholds.value?.[analysis.value?.aircraftClass ?? 'Medium'] ?? { light: 0.12, moderate: 0.14 }
)
const shownCategories: TurbulenceCategory[] = ['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme', 'NoData']
const worstPoint = computed<[number, number] | undefined>(() => {
  const s = analysis.value?.summary
  return s?.worstLat !== undefined && s.worstLon !== undefined && s.worstCategory !== 'Smooth'
    ? [s.worstLat, s.worstLon]
    : undefined
})
const arrival = computed(() =>
  analysis.value
    ? new Date(new Date(analysis.value.start).getTime() + analysis.value.durationMinutes * 60000).toISOString()
    : ''
)

function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
const fmt = (iso?: string) => (iso ? formatUtc(iso) : '–')
const localTime = (iso: string) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''

async function airportByCode(code: string): Promise<Place | undefined> {
  const airports = await searchAirports(code)
  const a = airports.find((x) => x.iata === code.toUpperCase() || x.icao === code.toUpperCase()) ?? airports[0]
  return a && { code: a.iata || a.icao, name: a.name, lat: a.location.lat, lon: a.location.lon }
}

async function analyze() {
  error.value = undefined
  loading.value = true
  try {
    let from = origin.value
    let to = destination.value
    if (mode.value === 'flight') {
      if (!flightQuery.value.trim()) return
      lookup.value = await lookupFlight(flightQuery.value.trim())
      if (!lookup.value.route) {
        error.value = lookup.value.live
          ? `${lookup.value.callsign} is flying, but its route isn't in the public database. Try the Route tab.`
          : `No route found for ${flightQuery.value}. Try the airline code plus number (UA1517) or the Route tab.`
        analysis.value = undefined
        return
      }
      from = lookup.value.route.origin
      to = lookup.value.route.destination
      router.replace({ query: { q: flightQuery.value.trim() } })
    } else {
      lookup.value = undefined
      if (!from || !to) {
        error.value = 'Pick both airports.'
        return
      }
      router.replace({ query: { from: from.code, to: to.code } })
    }
    analysis.value = await analyzeFlight({
      origin: from!,
      destination: to!,
      departure: new Date(departureLocal.value).toISOString(),
      aircraftClass: aircraftClass.value
    })
    analyzedOrigin.value = from
    analyzedDestination.value = to
  } catch (e) {
    error.value = `Analysis failed: ${e}`
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  fetchStatus()
    .then((s) => (allThresholds.value = s.thresholds))
    .catch(() => undefined)
  const { q, from, to } = route.query
  if (typeof q === 'string' && q) {
    flightQuery.value = q
    await analyze()
  } else if (typeof from === 'string' && typeof to === 'string') {
    mode.value = 'route'
    ;[origin.value, destination.value] = await Promise.all([airportByCode(from), airportByCode(to)])
    await analyze()
  }
})
</script>

<style scoped>
.analyze-page {
  width: 100%;
}
.map-card {
  height: 100%;
  min-height: 420px;
}
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
  font-size: 14px;
}
.stats span {
  display: block;
  font-size: 11px;
  color: var(--my-text-muted);
  text-transform: uppercase;
}
.cat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.cat-name {
  width: 80px;
}
.swatch {
  width: 14px;
  height: 10px;
  border-radius: 2px;
}
.muted {
  color: var(--my-text-muted);
}
</style>
