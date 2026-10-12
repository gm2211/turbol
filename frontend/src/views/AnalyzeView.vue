<template>
  <div class="analyze-page" :class="{ 'has-result': analysis && !flightId }">
    <!-- A downloaded flight: worked out on the phone, so it opens with no connection too. -->
    <DownloadedFlight v-if="flightId" :flight-id="flightId" />
    <DownloadedFlights v-else-if="!analysis && downloadedFlights.length > 0" />
    <!-- The search is a boarding pass: FROM/TO fill in once the flight is known, Analyze sits on the stub. -->
    <div v-if="!flightId" class="form-card">
      <v-btn-toggle v-model="mode" mandatory density="compact" variant="text" class="mode">
        <v-btn value="flight" prepend-icon="mdi-airplane">Flight</v-btn>
        <v-btn value="route" prepend-icon="mdi-map-marker-path">Route</v-btn>
      </v-btn-toggle>
      <div class="pass">
        <div class="pass-main">
          <div class="band">
            <span>Turbulence forecast</span>
            <span>Boarding · <b>analysis</b></span>
          </div>
          <div class="pass-body">
            <div class="ends">
              <div class="end">
                <div class="lbl">From</div>
                <div class="code" :class="{ ghost: !passFrom }">
                  {{ passFrom?.code ?? '- - -' }}
                </div>
                <div class="city">{{ passFrom?.name ?? placeholderCity }}</div>
              </div>
              <div class="arc"><v-icon icon="mdi-airplane" /></div>
              <div class="end to">
                <div class="lbl">To</div>
                <div class="code" :class="{ ghost: !passTo }">{{ passTo?.code ?? '- - -' }}</div>
                <div class="city">{{ passTo?.name ?? placeholderCity }}</div>
              </div>
            </div>
            <div class="form-row">
              <template v-if="mode === 'flight'">
                <v-text-field
                  v-model="flightQuery"
                  class="grow"
                  label="Flight number or callsign (UA1517, DAL2105)"
                  density="comfortable"
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
                label="Departs (local time)"
                density="comfortable"
                variant="outlined"
                hide-details
              />
            </div>
            <div v-if="lookup?.route" class="flight-line">
              <b>{{ lookup.route.iataCallsign ?? lookup.route.callsign }}</b>
              {{ lookup.route.airline ? `· ${lookup.route.airline}` : '' }}
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
                · its aircraft is in the air on another leg right now ({{
                  flightLevel(lookup.otherLeg.altitudeFt)
                }}, heading {{ compass(lookup.otherLeg.trackDeg) }}), not
                {{ lookup.route.origin.code }} →
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
            <v-alert v-if="error" type="warning" variant="tonal" density="compact">{{
              error
            }}</v-alert>
          </div>
        </div>
        <div class="stub">
          <v-btn class="go" color="#10324f" variant="flat" :loading="loading" @click="analyze">
            <v-icon icon="mdi-weather-windy" size="28" />
            <span>Analyze</span>
          </v-btn>
          <div class="barcode" />
        </div>
      </div>
    </div>

    <template v-if="analysis && !flightId">
      <v-card v-if="scheduleShown" class="panel flights-card px-3 py-2">
        <div class="flights-head">
          <span class="eyebrow">Flights {{ scheduleDay }}</span>
          <span v-if="scheduleLoading" class="muted small">
            <v-progress-circular indeterminate size="14" width="2" class="mr-1" />
            Finding this route's flights in the last day of ADS-B history…
          </span>
          <span v-else-if="schedule?.note" class="muted small">{{ schedule.note }}</span>
          <span v-else-if="schedule" class="muted small">
            Times and paths from each flight's last day of ADS-B tracks
          </span>
        </div>
        <div class="flight-chips">
          <button
            type="button"
            class="fchip"
            :class="{ active: !selectedFlight }"
            @click="pickFlight(undefined)"
          >
            <b>Any flight</b>
            <span class="muted">Great circle at {{ localTime(departureIso) }}</span>
          </button>
          <button
            v-for="f in schedule?.flights ?? []"
            :key="f.callsign + f.flownTakeoff"
            type="button"
            class="fchip"
            :class="{ active: selectedFlight?.callsign === f.callsign }"
            @click="pickFlight(f)"
          >
            <b>{{ f.flightNumber ?? f.callsign }}</b>
            <span class="times">{{ localTime(f.takeoff) }} → {{ localTime(f.landing) }}</span>
            <span class="muted">{{ [f.airline, f.typeCode].filter(Boolean).join(' · ') }}</span>
          </button>
        </div>
      </v-card>
      <v-card class="panel verdict-card pa-4">
        <div class="eyebrow">Forecast verdict</div>
        <v-chip
          :color="categoryColors[analysis.summary.worstCategory]"
          variant="flat"
          class="mb-2"
          label
        >
          Worst: {{ categoryLabels[analysis.summary.worstCategory] }}
        </v-chip>
        <div class="verdict mb-3">{{ analysis.summary.verdict }}</div>
        <v-btn
          v-if="downloaded"
          size="small"
          :color="downloadedStatus?.ready ? 'green-darken-2' : '#10324f'"
          variant="flat"
          class="mb-3"
          :prepend-icon="downloadedStatus?.ready ? 'mdi-check-circle' : 'mdi-download'"
          :to="{ path: '/analyze', query: { flight: downloaded.id } }"
        >
          {{ downloadedStatus?.ready ? 'Open downloaded flight' : downloadedStatus?.text }}
        </v-btn>
        <v-btn
          v-else
          size="small"
          color="#10324f"
          variant="flat"
          class="mb-3"
          prepend-icon="mdi-download"
          :loading="downloading"
          @click="downloadForOffline"
        >
          Download for offline
        </v-btn>
        <div class="stats">
          <div>
            <span>Distance</span>{{ Math.round(analysis.totalDistanceKm).toLocaleString() }} km
          </div>
          <div><span>Flight time</span>{{ formatDuration(analysis.durationMinutes) }}</div>
          <div><span>Cruise</span>{{ flightLevel(analysis.cruiseAltitudeFt) }}</div>
          <div><span>Aircraft</span>{{ aircraftLabel }}</div>
          <div class="wide"><span>Ride</span>{{ responseLabel(response) }}</div>
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
          :path-label="analysis.path?.description"
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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AirportPicker from '@/components/analysis/AirportPicker.vue'
import DownloadedFlight from '@/components/fly/DownloadedFlight.vue'
import DownloadedFlights from '@/components/fly/DownloadedFlights.vue'
import RouteMap from '@/components/analysis/RouteMap.vue'
import TimelineBar from '@/components/analysis/TimelineBar.vue'
import TurbulenceProfile from '@/components/analysis/TurbulenceProfile.vue'
import {
  type FlightLookup,
  type Place,
  type RouteAnalysis,
  airportByCode,
  type RouteSchedule,
  type ScheduledFlight,
  analyzeFlight,
  fetchSchedule,
  formatDuration,
  lookupFlight
} from '@/api/flights'
import {
  type AircraftClass,
  type Thresholds,
  type TurbulenceCategory,
  categoryColors,
  categoryLabels,
  classResponse,
  fetchStatus,
  flightLevel,
  formatUtc,
  responseLabel,
  scaleThresholds
} from '@/api/turbulence'
import { downloads, packStatus } from '@/fly/downloads'
import { newFlight } from '@/fly/flights'

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
const downloading = ref(false)
const allThresholds = ref<Record<AircraftClass, Thresholds>>()
/** The route's flights on the chosen day (Route tab), and the one being analyzed (none: a great circle). */
const schedule = ref<RouteSchedule>()
const scheduleLoading = ref(false)
const selectedFlight = ref<ScheduledFlight>()
/** Callsign to pick once the schedule arrives (`?pick=DAL184`), else the first flight from the chosen time on. */
let pickWanted: string | undefined
let scheduleKey = ''
let retryTimer: number | undefined
const downloadedFlights = downloads.flights
/** A flight downloaded for offline use, to show instead of the form: `?flight=<id>`. */
const flightId = computed(() =>
  typeof route.query.flight === 'string' ? route.query.flight : undefined
)
/** What the result on screen was analyzed for, as the query has it: `q:UA1517` or `route:JFK-LAX`. */
let shown: string | undefined

/** How strongly this aircraft feels turbulence: its wing loading, or its size class. */
const response = computed(
  () => analysis.value?.aircraft ?? classResponse(analysis.value?.aircraftClass ?? 'Medium')
)
/** The profile's Light and Moderate lines, in the EDR this aircraft would feel as each. */
const thresholds = computed(() =>
  scaleThresholds(
    allThresholds.value?.Medium ?? { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 },
    response.value.factor
  )
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
/** The flight on screen, as it would be downloaded for offline use. */
const toDownload = computed(() => {
  const a = analysis.value
  const from = analyzedOrigin.value
  const to = analyzedDestination.value
  if (!a || !from || !to) return undefined
  const found = lookup.value?.route
  const picked = selectedFlight.value
  return newFlight({
    label: found
      ? (found.iataCallsign ?? found.callsign)
      : picked
        ? (picked.flightNumber ?? picked.callsign)
        : `${from.code} → ${to.code}`,
    callsign: lookup.value?.callsign ?? picked?.callsign,
    origin: from,
    destination: to,
    departure: new Date(a.start).getTime(),
    aircraftClass: a.aircraftClass,
    aircraftType: a.aircraftType,
    aircraft: a.aircraft,
    cruiseFt: a.cruiseAltitudeFt
  })
})
/** That flight, if it is already on the phone. */
const downloaded = computed(() =>
  downloadedFlights.value.find((f) => f.id === toDownload.value?.id)
)
const downloadedStatus = computed(
  () => downloaded.value && packStatus(downloaded.value, downloads.states[downloaded.value.id])
)
/** The airports on the pass: the ones picked on the Route tab, or the looked-up flight's. */
const passFrom = computed(() =>
  mode.value === 'route' ? origin.value : lookup.value?.route?.origin
)
const passTo = computed(() =>
  mode.value === 'route' ? destination.value : lookup.value?.route?.destination
)
const placeholderCity = computed(() =>
  mode.value === 'route' ? 'Pick an airport' : 'Filled from the flight'
)
const arrival = computed(() =>
  analysis.value
    ? new Date(
        new Date(analysis.value.start).getTime() + analysis.value.durationMinutes * 60000
      ).toISOString()
    : ''
)

const departureIso = computed(() => new Date(departureLocal.value).toISOString())
const scheduleShown = computed(
  () => mode.value === 'route' && (scheduleLoading.value || !!schedule.value)
)
/** The chosen day, as the strip's title says it. */
const scheduleDay = computed(() =>
  new Date(departureLocal.value).toLocaleDateString([], {
    weekday: 'short',
    month: 'short',
    day: 'numeric'
  })
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
      shown = `q:${flightQuery.value.trim()}`
      router.replace({ query: { q: flightQuery.value.trim() } })
    } else {
      lookup.value = undefined
      if (!from || !to) {
        error.value = 'Pick both airports.'
        return
      }
      const day = departureLocal.value.slice(0, 10)
      shown = `route:${from.code}-${to.code}:${day}`
      router.replace({ query: { from: from.code, to: to.code, day } })
      selectedFlight.value = undefined
    }
    analysis.value = await analyzeFlight({
      origin: from!,
      destination: to!,
      departure: new Date(departureLocal.value).toISOString(),
      callsign: mode.value === 'flight' ? lookup.value?.callsign : undefined
    })
    analyzedOrigin.value = from
    analyzedDestination.value = to
    scheduleRetry()
    if (mode.value === 'route') void loadSchedule(from!, to!)
    else schedule.value = undefined
  } catch (e) {
    error.value = `Analysis failed: ${e}`
  } finally {
    loading.value = false
  }
}

/** The local day of the departure field, as an instant range. */
function chosenDay(): [Date, Date] {
  const start = new Date(departureLocal.value.slice(0, 10) + 'T00:00')
  const end = new Date(start)
  end.setDate(end.getDate() + 1)
  return [start, end]
}

/** Lists the route's flights on the chosen day, then analyzes the wanted one (or the next from the chosen time). */
async function loadSchedule(from: Place, to: Place) {
  const [start, end] = chosenDay()
  const key = `${from.code}-${to.code}-${start.toISOString()}`
  if (key === scheduleKey && schedule.value) return pickDefault()
  scheduleKey = key
  schedule.value = undefined
  scheduleLoading.value = true
  try {
    const result = await fetchSchedule(from, to, start, end)
    if (key !== scheduleKey) return
    schedule.value = result
    await pickDefault()
  } catch {
    if (key === scheduleKey)
      schedule.value = {
        flights: [],
        candidates: 0,
        slotsScanned: 0,
        note: "Couldn't load this route's flights right now."
      }
  } finally {
    if (key === scheduleKey) scheduleLoading.value = false
  }
}

async function pickDefault() {
  const flights = schedule.value?.flights ?? []
  const wanted = pickWanted
  pickWanted = undefined
  const chosen = new Date(departureLocal.value).getTime()
  const f = wanted
    ? flights.find((x) => x.callsign === wanted || x.flightNumber === wanted)
    : (flights.find((x) => new Date(x.takeoff).getTime() >= chosen - 30 * 60000) ?? flights[0])
  if (f) await pickFlight(f)
}

/** Analyzes one of the route's flights along the track it flew, at its time on the chosen day; none: a great circle. */
async function pickFlight(f: ScheduledFlight | undefined) {
  const from = analyzedOrigin.value
  const to = analyzedDestination.value
  if (!from || !to) return
  selectedFlight.value = f
  router.replace({ query: { ...route.query, pick: f?.callsign } })
  loading.value = true
  error.value = undefined
  try {
    const result = await analyzeFlight(
      f
        ? {
            origin: from,
            destination: to,
            departure: f.takeoff,
            callsign: f.callsign,
            flown: { hex: f.hex, takeoff: f.flownTakeoff }
          }
        : { origin: from, destination: to, departure: departureIso.value }
    )
    if (selectedFlight.value === f) analysis.value = result
    scheduleRetry()
  } catch (e) {
    error.value = `Analysis failed: ${e}`
  } finally {
    loading.value = false
  }
}

/** Right after a server update the forecast fills in for a few minutes: analyze again once it has. */
function scheduleRetry() {
  window.clearTimeout(retryTimer)
  if (!analysis.value?.dataSources.loading?.length) return
  retryTimer = window.setTimeout(() => {
    if (mode.value === 'route' && analyzedOrigin.value) void pickFlight(selectedFlight.value)
    else void analyze()
  }, 60000)
}

/** Downloads this flight for offline use, then opens it: the same page works with no connection. */
async function downloadForOffline() {
  const flight = toDownload.value
  if (!flight) return
  downloading.value = true
  try {
    const saved = await downloads.download({ ...flight, savedAt: Date.now() })
    await router.push({ path: '/analyze', query: { flight: saved.id } })
  } catch (e) {
    error.value = `Couldn't download the flight on this phone: ${e}`
  } finally {
    downloading.value = false
  }
}

/** Shows what the query asks for: a link, Back or Forward, or the Analyze tab (no query: the start page). */
async function fromQuery() {
  if (route.path !== '/analyze' || flightId.value) return
  const { q, from, to, day, pick } = route.query
  const key =
    typeof q === 'string' && q
      ? `q:${q}`
      : typeof from === 'string' && typeof to === 'string'
        ? `route:${from}-${to}:${day ?? ''}`
        : undefined
  if (key === shown) return
  shown = key
  pickWanted = typeof pick === 'string' && pick ? pick : undefined
  // ?day=YYYY-MM-DD (the live map's route search for another day): that day, at the current time of day.
  if (typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day))
    departureLocal.value = day + toLocalInput(new Date()).slice(10)
  if (typeof q === 'string' && q) {
    mode.value = 'flight'
    flightQuery.value = q
    await analyze()
  } else if (typeof from === 'string' && typeof to === 'string') {
    mode.value = 'route'
    ;[origin.value, destination.value] = await Promise.all([airportByCode(from), airportByCode(to)])
    await analyze()
  } else {
    analysis.value = undefined
    lookup.value = undefined
    error.value = undefined
  }
}

watch(() => route.fullPath, fromQuery)
onBeforeUnmount(() => window.clearTimeout(retryTimer))

onMounted(async () => {
  fetchStatus()
    .then((s) => (allThresholds.value = s.thresholds))
    .catch(() => undefined)
  await fromQuery()
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
    grid-template-rows: auto auto minmax(0, 1fr) auto;
    grid-template-areas:
      'form form'
      'flights flights'
      'verdict map'
      'timeline timeline';
  }
  .form-card {
    grid-area: form;
    min-width: 0;
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
  .flights-card {
    grid-area: flights;
    min-width: 0;
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
/* The boarding pass. Ticket notches are cut out of both halves with masks, so the sky shows through. */
.form-card {
  --notch: 13px;
  --navy: #10324f;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.analyze-page:not(.has-result) .form-card {
  width: 100%;
  max-width: 1000px;
  margin: min(8vh, 72px) auto 0;
}
.mode {
  align-self: flex-start;
  height: 40px;
  background: rgba(16, 50, 79, 0.6);
  backdrop-filter: blur(8px);
  border-radius: 10px;
  padding: 3px;
}
.mode .v-btn {
  background: transparent;
  color: rgba(255, 255, 255, 0.85);
  border-radius: 8px !important;
  text-transform: none;
  letter-spacing: 0;
  font-weight: 600;
}
.mode .v-btn.v-btn--active {
  background: white;
  color: var(--navy);
}
.mode .v-btn :deep(.v-btn__overlay) {
  opacity: 0;
}
.pass {
  display: flex;
  filter: drop-shadow(0 18px 32px rgba(16, 50, 79, 0.4));
}
.pass-main,
.stub {
  background: rgba(255, 255, 255, 0.94);
  color: #0f172a;
}
.pass-main {
  flex: 1;
  min-width: 0;
  border-radius: 20px 0 0 20px;
  overflow: hidden;
  mask:
    radial-gradient(circle var(--notch) at 100% 0, #0000 98%, #000) top / 100% 51% no-repeat,
    radial-gradient(circle var(--notch) at 100% 100%, #0000 98%, #000) bottom / 100% 51% no-repeat;
}
.band {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  background: var(--navy);
  color: white;
  padding: 10px 24px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}
.band b {
  color: rgb(127, 199, 184);
}
.pass-body {
  padding: 18px 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ends {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(16, 50, 79, 0.1);
}
.end.to {
  text-align: right;
}
.lbl {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #475569;
}
.code {
  font-size: 52px;
  font-weight: 800;
  line-height: 1.05;
  letter-spacing: 0.02em;
  color: var(--navy);
}
.code.ghost {
  color: rgba(16, 50, 79, 0.16);
}
.city {
  font-size: 13px;
  color: #475569;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.arc {
  width: 180px;
  border-top: 2px dashed rgba(91, 143, 178, 0.6);
  position: relative;
  margin-top: 6px;
}
.arc .v-icon {
  position: absolute;
  top: -14px;
  left: 50%;
  transform: translateX(-50%) rotate(90deg);
  font-size: 26px;
  color: var(--navy);
  background: white;
  padding: 0 4px;
  box-sizing: content-box;
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
  flex: 0 0 240px;
}
.flight-line {
  font-size: 15px;
  line-height: 32px;
}
.stub {
  width: 190px;
  flex: none;
  border-radius: 0 20px 20px 0;
  border-left: 2px dashed rgba(16, 50, 79, 0.25);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 18px;
  mask:
    radial-gradient(circle var(--notch) at 0 0, #0000 98%, #000) top / 100% 51% no-repeat,
    radial-gradient(circle var(--notch) at 0 100%, #0000 98%, #000) bottom / 100% 51% no-repeat;
}
.stub .go {
  width: 100%;
  height: 104px;
  border-radius: 14px;
  text-transform: none;
  letter-spacing: 0;
  font-size: 16px;
  font-weight: 600;
  box-shadow: 0 4px 14px rgba(16, 50, 79, 0.35);
}
.stub .go :deep(.v-btn__content) {
  flex-direction: column;
  gap: 6px;
}
.barcode {
  width: 100%;
  height: 32px;
  border-radius: 2px;
  opacity: 0.5;
  background: repeating-linear-gradient(
    90deg,
    var(--navy) 0 2px,
    transparent 2px 4px,
    var(--navy) 4px 7px,
    transparent 7px 9px
  );
}
/* With a result on a laptop the pass folds into one slim row, leaving the screen to the result. */
@media (min-width: 960px) {
  .has-result .band,
  .has-result .barcode {
    display: none;
  }
  .has-result .pass-body {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    padding: 10px 18px;
    gap: 10px 18px;
  }
  .has-result .ends {
    border: 0;
    padding: 0;
    gap: 12px;
  }
  .has-result .code {
    font-size: 28px;
  }
  .has-result .city {
    max-width: 140px;
  }
  .has-result .arc {
    width: 56px;
  }
  .has-result .form-row {
    flex: 1 1 480px;
  }
  .has-result .flight-line,
  .has-result .v-alert {
    flex: 1 1 100%;
  }
  .has-result .pass-main {
    border-radius: 16px 0 0 16px;
  }
  .has-result .stub {
    width: 150px;
    padding: 12px;
    border-radius: 0 16px 16px 0;
  }
  .has-result .stub .go {
    height: 56px;
  }
  .has-result .stub .go :deep(.v-btn__content) {
    flex-direction: row;
  }
}
/* Phones: the stub tears off along the bottom. */
@media (max-width: 760px) {
  .pass {
    flex-direction: column;
  }
  .pass-main {
    border-radius: 18px 18px 0 0;
    mask:
      radial-gradient(circle var(--notch) at 0 100%, #0000 98%, #000) left / 51% 100% no-repeat,
      radial-gradient(circle var(--notch) at 100% 100%, #0000 98%, #000) right / 51% 100% no-repeat;
  }
  .stub {
    width: auto;
    border-left: 0;
    border-top: 2px dashed rgba(16, 50, 79, 0.25);
    border-radius: 0 0 18px 18px;
    padding: 14px 16px 16px;
    mask:
      radial-gradient(circle var(--notch) at 0 0, #0000 98%, #000) left / 51% 100% no-repeat,
      radial-gradient(circle var(--notch) at 100% 0, #0000 98%, #000) right / 51% 100% no-repeat;
  }
  .stub .go {
    height: 52px;
  }
  .stub .go :deep(.v-btn__content) {
    flex-direction: row;
  }
  .barcode {
    display: none;
  }
  .band {
    padding: 9px 16px;
    font-size: 10px;
  }
  .pass-body {
    padding: 14px 16px 16px;
  }
  .code {
    font-size: 38px;
  }
  .arc {
    width: 56px;
  }
  .form-row .departure {
    flex: 1 1 100%;
  }
  .analyze-page:not(.has-result) .form-card {
    margin-top: 8px;
  }
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
/* The route's flights: one row of ticket stubs, scrolling sideways when there are many. */
.flights-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-bottom: 6px;
}
.small {
  font-size: 13px;
}
.flight-chips {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.fchip {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  min-width: 150px;
  padding: 6px 12px;
  border: 1.5px solid rgba(16, 50, 79, 0.25);
  border-radius: 10px;
  background: white;
  color: #0f172a;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.fchip b {
  font-size: 15px;
  color: #10324f;
}
.fchip .times {
  font-variant-numeric: tabular-nums;
}
.fchip.active {
  background: #10324f;
  border-color: #10324f;
  color: white;
}
.fchip.active b,
.fchip.active .muted {
  color: white;
}
</style>
