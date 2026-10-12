<template>
  <div class="analyze-page" :class="{ 'has-result': analysis }">
    <v-card class="panel form-card px-4 py-3">
      <div class="form-row">
        <v-btn-toggle v-model="mode" mandatory density="compact" variant="outlined" divided class="mode">
          <v-btn value="flight">Flight</v-btn>
          <v-btn value="route">Route</v-btn>
        </v-btn-toggle>
        <template v-if="mode === 'flight'">
          <v-text-field
            v-model="flightQuery"
            class="grow"
            label="Flight number or callsign (UA1517, DAL2105)"
            density="compact"
            variant="outlined"
            hide-details
            @keyup.enter="analyze"
          />
        </template>
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
        <v-btn color="#10324f" :loading="loading" height="40" @click="analyze">Analyze</v-btn>
      </div>
      <div v-if="lookup?.route" class="flight-line mt-2">
        <b>{{ lookup.route.iataCallsign ?? lookup.route.callsign }}</b>
        {{ lookup.route.airline ? `· ${lookup.route.airline}` : '' }} ·
        {{ lookup.route.origin.code }} → {{ lookup.route.destination.code }}
        <template v-if="lookup.live">
          <v-chip size="small" color="green-darken-2" variant="flat" class="ml-2">
            In the air now · {{ flightLevel(lookup.live.altitudeFt) }}
          </v-chip>
          <v-btn
            size="small"
            color="green-darken-2"
            variant="flat"
            class="ml-2"
            prepend-icon="mdi-crosshairs-gps"
            :to="`/map?follow=${lookup.live.hex}`"
          >
            Follow live
          </v-btn>
        </template>
        <span v-else-if="lookup.otherLeg">
          · its aircraft is in the air on another leg right now ({{ flightLevel(lookup.otherLeg.altitudeFt) }},
          heading {{ compass(lookup.otherLeg.trackDeg) }}), not {{ lookup.route.origin.code }} →
          {{ lookup.route.destination.code }}
        </span>
        <v-btn
          size="small"
          color="#10324f"
          variant="flat"
          class="ml-2"
          prepend-icon="mdi-cube-outline"
          :to="`/map?view=3d&flights=${encodeURIComponent(flightQuery.trim().toUpperCase())}`"
        >
          View in 3D
        </v-btn>
      </div>
      <v-alert v-if="error" type="warning" variant="tonal" class="mt-2" density="compact">{{ error }}</v-alert>
    </v-card>

    <template v-if="analysis">
      <v-card class="panel verdict-card pa-4">
        <div class="eyebrow">Forecast verdict</div>
        <v-chip :color="categoryColors[analysis.summary.worstCategory]" variant="flat" class="mb-2" label>
          Worst: {{ categoryLabels[analysis.summary.worstCategory] }}
        </v-chip>
        <div class="verdict mb-3">{{ analysis.summary.verdict }}</div>
        <div class="stats">
          <div><span>Distance</span>{{ Math.round(analysis.totalDistanceKm).toLocaleString() }} km</div>
          <div><span>Flight time</span>{{ formatDuration(analysis.durationMinutes) }}</div>
          <div><span>Cruise</span>{{ flightLevel(analysis.cruiseAltitudeFt) }}</div>
          <div><span>Aircraft</span>{{ aircraftLabel }}</div>
          <div class="wide">
            <span>Max EDR</span>{{ analysis.summary.maxEdr?.toFixed(3) ?? '–' }}
            <template v-if="analysis.summary.worstMinute !== undefined">
              at {{ formatDuration(analysis.summary.worstMinute) }}
            </template>
            <template v-if="worstPoint"> · <span class="ring" /> on the map</template>
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
          class="mt-2 note"
          >{{ n }}</v-alert
        >
        <div class="muted sources mt-3">
          GTG-N nowcast {{ fmt(analysis.dataSources.nowcastValid) }} · GTG forecast issued
          {{ fmt(analysis.dataSources.forecastIssued) }}, valid until
          {{ fmt(analysis.dataSources.forecastUntil) }}
          <template v-if="analysis.dataSources.globalIssued">
            · outside the US: WAFS + NOAA GFS (~28 km), cycle
            {{ fmt(analysis.dataSources.globalIssued) }}
          </template>
        </div>
      </v-card>
      <v-card class="panel map-card pa-2">
        <RouteMap
          :points="analysis.points"
          :origin="analyzedOrigin"
          :destination="analyzedDestination"
          :worst="worstPoint"
          :worst-label="worstLabel"
          :aircraft="lookup?.live"
          legend
        />
      </v-card>
      <v-card class="panel timeline-card px-4 py-2">
        <div class="eyebrow mb-1">Turbulence timeline</div>
        <TimelineBar
          :segments="analysis.segments"
          :start-label="`Departure ${localTime(analysis.start)}`"
          :end-label="`Arrival ${localTime(arrival)}`"
        />
        <TurbulenceProfile
          class="mt-1"
          :points="analysis.points"
          :light="thresholds.light"
          :moderate="thresholds.moderate"
          :height="100"
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
const aircraftLabel = computed(() => {
  const a = analysis.value
  if (!a) return ''
  return a.aircraftType ? `${a.aircraftType} · ${a.aircraftClass}` : `${a.aircraftClass} (assumed)`
})
/** Categories this flight actually has (Smooth always), so the breakdown stays short enough for one screen. */
const shownCategories = computed(() =>
  (['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme', 'NoData'] as TurbulenceCategory[]).filter(
    (c) => c === 'Smooth' || (analysis.value?.summary.minutesByCategory[c] ?? 0) > 0
  )
)
const worstPoint = computed<[number, number] | undefined>(() => {
  const s = analysis.value?.summary
  return s?.worstLat !== undefined && s.worstLon !== undefined && s.worstCategory !== 'Smooth'
    ? [s.worstLat, s.worstLon]
    : undefined
})
const worstLabel = computed(() => {
  const s = analysis.value?.summary
  if (!s || !worstPoint.value) return undefined
  const at = s.worstMinute !== undefined ? ` at ${formatDuration(s.worstMinute)}` : ''
  return `Worst forecast bump: ${categoryLabels[s.worstCategory]}${at}`
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
const compass = (deg?: number) =>
  deg === undefined ? '?' : ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'][Math.round(deg / 45) % 8]
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
      callsign: mode.value === 'flight' ? lookup.value?.callsign : undefined
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
/* One screen on a laptop: form on top, verdict beside the map, timeline strip along the bottom. */
.analyze-page {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
@media (min-width: 960px) {
  .analyze-page.has-result {
    height: calc(100vh - 72px);
    display: grid;
    grid-template-columns: minmax(320px, 26%) 1fr;
    grid-template-rows: auto minmax(0, 1fr) auto;
    grid-template-areas:
      'form form'
      'verdict map'
      'timeline timeline';
  }
  .form-card {
    grid-area: form;
  }
  .verdict-card {
    grid-area: verdict;
    overflow-y: auto;
  }
  .map-card {
    grid-area: map;
    min-height: 0;
    overflow: hidden;
  }
  .map-card :deep(.route-map) {
    min-height: 0;
  }
  .timeline-card {
    grid-area: timeline;
  }
}
/* Near-opaque light panels: dark text stays readable over the cloud photo. */
.v-card.panel {
  background: rgba(255, 255, 255, 0.93);
  color: #0f172a;
  backdrop-filter: blur(6px);
  border-radius: 10px;
}
.map-card {
  display: flex;
}
@media (max-width: 959px) {
  .map-card {
    min-height: 420px;
  }
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
.flight-line {
  font-size: 15px;
  line-height: 32px;
}
.eyebrow {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #334155;
}
.verdict {
  font-size: 18px;
  font-weight: 500;
  line-height: 1.35;
}
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
  font-size: 15px;
}
.stats .wide {
  grid-column: span 2;
}
.stats > div > span:first-child {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: #475569;
  text-transform: uppercase;
}
.ring {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2.5px solid #dc2626;
  border-radius: 50%;
  vertical-align: -1px;
}
.cat-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}
.cat-name {
  width: 84px;
}
.swatch {
  width: 14px;
  height: 10px;
  border-radius: 2px;
}
.muted {
  color: #475569;
}
.sources {
  font-size: 13px;
}
.note {
  font-size: 14px;
}
</style>
