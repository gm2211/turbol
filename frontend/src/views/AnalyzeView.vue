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
              <template v-if="dated">
                · {{ dated.origin }} {{ dated.departureLocal }} → {{ dated.destination }}
                {{ dated.arrivalLocal
                }}{{ dated.arrivalDayOffset > 0 ? ` +${dated.arrivalDayOffset}` : '' }}
                <span v-if="dated.equipment" class="muted">· {{ dated.equipment }}</span>
                <span v-if="!dated.scheduledOnDay" class="muted">
                  · not scheduled on {{ scheduleDay }}; it flies {{ operatingDaysLabel }} at these
                  times
                </span>
              </template>
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
      <v-card v-if="scheduleShown || pathOptions.length" class="panel flights-card px-3 py-2">
        <template v-if="scheduleShown">
          <div class="flights-head">
            <span class="eyebrow">Flights {{ scheduleDay }}</span>
            <span v-if="scheduleLoading" class="muted small">
              <v-progress-circular indeterminate size="14" width="2" class="mr-1" />
              Finding this route's flights and their recent tracks…
            </span>
            <span v-else-if="schedule?.note" class="muted small">{{ schedule.note }}</span>
            <span v-else-if="schedule?.timetableDay" class="muted small">
              Timetable for {{ timetableDayLabel }} (same weekday; later days aren't published yet)
              · local times at each airport
            </span>
            <span v-else-if="schedule" class="muted small">
              Scheduled local times at each airport
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
              <span class="muted">Departing {{ localTime(departureIso) }}</span>
            </button>
            <button
              v-for="f in schedule?.flights ?? []"
              :key="f.callsign + f.departure"
              type="button"
              class="fchip"
              :class="{ active: selectedFlight === f }"
              :title="f.codeshares.length ? `Also sold as ${f.codeshares.join(', ')}` : undefined"
              @click="pickFlight(f)"
            >
              <b>{{ f.flightNumber ?? f.callsign }}</b>
              <span class="times">{{ flightTimes(f) }}</span>
              <span class="muted">{{ [f.airline, f.typeCode].filter(Boolean).join(' · ') }}</span>
            </button>
          </div>
        </template>
        <div class="path-row" :class="{ 'mt-2': scheduleShown }">
          <span class="eyebrow path-title">Path</span>
          <button
            v-for="o in pathOptions"
            :key="o.kind"
            type="button"
            class="pchip"
            :class="{ active: analysis.path?.kind === o.kind }"
            :disabled="!o.available"
            :title="o.note"
            @click="choosePath(o.kind)"
          >
            <b>{{ o.label }}</b>
            <span class="pnote">{{ o.note }}</span>
          </button>
        </div>
      </v-card>
      <!-- The verdict is a second boarding pass: worst category → when, torn from tabs that never scroll. -->
      <div class="verdict-card vpass">
        <div class="vp-stub">
          <div class="eyebrow">Forecast verdict</div>
          <div class="vp-ends">
            <div>
              <div class="vp-code" :style="{ color: worstColor }">{{ worstCode }}</div>
              <div class="vp-sub">Worst: {{ categoryLabels[analysis.summary.worstCategory] }}</div>
            </div>
            <div class="vp-arc"><v-icon icon="mdi-airplane" /></div>
            <div class="vp-right">
              <div class="vp-code">{{ worstAt.code }}</div>
              <div class="vp-sub">{{ worstAt.label }}</div>
            </div>
          </div>
          <div class="verdict">{{ analysis.summary.verdict }}</div>
          <div v-if="pathLabel" class="muted small mt-1">
            Along: <b>{{ pathLabel }}</b>
          </div>
        </div>
        <div class="vp-perf" />
        <div class="vp-main">
          <div class="vp-tabs" role="tablist">
            <button
              v-for="t in verdictTabs"
              :key="t.id"
              type="button"
              role="tab"
              :aria-selected="verdictTab === t.id"
              :class="{ on: verdictTab === t.id }"
              @click="verdictTab = t.id"
            >
              {{ t.label }}
            </button>
          </div>
          <div class="vp-panes">
            <div class="vp-pane" :class="{ off: verdictTab !== 'summary' }">
              <div class="vp-lbl">Route at a glance</div>
              <div class="vp-glance">
                <div
                  v-for="(s, i) in analysis.segments"
                  :key="i"
                  class="vp-seg"
                  :style="{
                    left: `${(100 * s.startMinute) / analysis.durationMinutes}%`,
                    width: `${(100 * (s.endMinute - s.startMinute)) / analysis.durationMinutes}%`,
                    background: categoryColors[s.category]
                  }"
                />
                <span
                  v-if="worstPoint && analysis.summary.worstMinute !== undefined"
                  class="ring vp-worst"
                  :style="{
                    left: `${(100 * analysis.summary.worstMinute) / analysis.durationMinutes}%`
                  }"
                />
                <span
                  v-if="hoverMinute !== undefined"
                  class="vp-cursor"
                  :style="{ left: `${(100 * hoverMinute) / analysis.durationMinutes}%` }"
                />
              </div>
              <div class="vp-glance-ends">
                <span>{{ analyzedOrigin?.code }} {{ localTime(analysis.start) }}</span>
                <span>Max EDR {{ analysis.summary.maxEdr?.toFixed(3) ?? '–' }}</span>
                <span>{{ analyzedDestination?.code }} {{ localTime(arrival) }}</span>
              </div>
              <div class="vp-fields">
                <div>
                  <span>Distance</span
                  ><b>{{ Math.round(analysis.totalDistanceKm).toLocaleString() }} km</b>
                </div>
                <div>
                  <span>Flight time</span><b>{{ formatDuration(analysis.durationMinutes) }}</b>
                </div>
                <div>
                  <span>Cruise</span><b>{{ flightLevel(analysis.cruiseAltitudeFt) }}</b>
                </div>
                <div>
                  <span>Aircraft</span><b>{{ aircraftLabel }}</b>
                </div>
              </div>
              <div v-if="response.basis === 'type'" class="muted small mt-2">
                <b>Ride:</b> {{ responseLabel(response) }}
              </div>
              <div class="vp-acts">
                <v-btn
                  v-if="downloaded"
                  :color="downloadedStatus?.ready ? 'green-darken-2' : '#10324f'"
                  variant="flat"
                  :prepend-icon="downloadedStatus?.ready ? 'mdi-check-circle' : 'mdi-download'"
                  :to="{ path: '/analyze', query: { flight: downloaded.id } }"
                >
                  {{ downloadedStatus?.ready ? 'Open downloaded flight' : downloadedStatus?.text }}
                </v-btn>
                <v-btn
                  v-else
                  color="#10324f"
                  variant="flat"
                  prepend-icon="mdi-download"
                  :loading="downloading"
                  @click="downloadForOffline"
                >
                  Download for offline
                </v-btn>
                <v-btn
                  variant="outlined"
                  color="#10324f"
                  prepend-icon="mdi-cube-outline"
                  @click="explore3d"
                >
                  3D
                </v-btn>
              </div>
            </div>
            <div class="vp-pane" :class="{ off: verdictTab !== 'bumps' }">
              <div v-for="c in shownCategories" :key="c" class="vp-cat">
                <span class="swatch" :style="{ background: categoryColors[c] }" />
                <span class="cat-name">{{ categoryLabels[c] }}</span>
                <span class="vp-track"
                  ><i
                    :style="{
                      width: `${analysis.summary.percentByCategory[c] ?? 0}%`,
                      background: categoryColors[c]
                    }"
                /></span>
                <b class="tnum">{{ formatDuration(analysis.summary.minutesByCategory[c] ?? 0) }}</b>
                <span class="muted tnum vp-pc"
                  >{{ analysis.summary.percentByCategory[c] ?? 0 }}%</span
                >
              </div>
              <div class="vp-lbl mt-3">When the bumps come</div>
              <div v-if="bumpyStretches.length === 0" class="muted small">
                No bumpy stretches forecast.
              </div>
              <button
                v-for="b in bumpyStretches"
                :key="b.start"
                type="button"
                class="vp-stretch"
                :class="{
                  on: hoverMinute !== undefined && hoverMinute >= b.start && hoverMinute <= b.end
                }"
                @click="hoverMinute = (b.start + b.end) / 2"
              >
                <span class="swatch" :style="{ background: categoryColors[b.category] }" />
                <b>{{ categoryLabels[b.category] }}</b>
                <span class="tnum"
                  >{{ formatDuration(b.start) }} → {{ formatDuration(b.end) }}</span
                >
                <span class="muted tnum ml-auto"
                  >{{ clockAt(b.start) }} · {{ formatDuration(b.end - b.start) }}</span
                >
              </button>
            </div>
            <div class="vp-pane" :class="{ off: verdictTab !== 'calm' }">
              <div class="vp-lbl">Calm windows to get up</div>
              <div v-if="calmWindows.length === 0" class="muted small">
                No smooth stretch of 20 minutes or more forecast.
              </div>
              <button
                v-for="(w, i) in calmWindows"
                :key="w.start"
                type="button"
                class="vp-stretch"
                :class="{
                  on: hoverMinute !== undefined && hoverMinute >= w.start && hoverMinute <= w.end
                }"
                @click="hoverMinute = (w.start + w.end) / 2"
              >
                <v-icon
                  :icon="i === 0 ? 'mdi-star-four-points' : 'mdi-weather-sunny'"
                  size="16"
                  color="#16a34a"
                />
                <b class="tnum">{{ formatDuration(w.end - w.start) }}</b>
                <span class="tnum"
                  >{{ formatDuration(w.start) }} → {{ formatDuration(w.end) }}</span
                >
                <span class="muted tnum ml-auto">{{ clockAt(w.start) }}–{{ clockAt(w.end) }}</span>
              </button>
              <div class="vp-foot">
                <div v-for="n in analysis.summary.notes" :key="n" class="vp-note">
                  <v-icon icon="mdi-information-outline" size="14" />{{ n }}
                </div>
                <div class="muted">
                  GTG-N nowcast {{ fmt(analysis.dataSources.nowcastValid) }} · GTG forecast issued
                  {{ fmt(analysis.dataSources.forecastIssued) }}, valid until
                  {{ fmt(analysis.dataSources.forecastUntil) }}
                  <template v-if="analysis.dataSources.globalIssued">
                    · outside the US: WAFS + NOAA GFS (~28 km), cycle
                    {{ fmt(analysis.dataSources.globalIssued) }}
                  </template>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <v-card class="panel map-card pa-2">
        <RouteMap
          :points="analysis.points"
          :origin="analyzedOrigin"
          :destination="analyzedDestination"
          :worst="worstPoint"
          :worst-label="worstLabel"
          :aircraft="lookup?.live"
          :path-label="analysis.path?.description"
          :cursor="hoverPoint"
          legend
        />
      </v-card>
      <v-card class="panel timeline-card px-4 py-2">
        <div class="eyebrow mb-1">
          Turbulence timeline<span v-if="pathLabel" class="path-tag"> · along {{ pathLabel }}</span>
        </div>
        <TimelineBar
          :segments="analysis.segments"
          :start-label="`Departure ${localTime(analysis.start)}`"
          :end-label="`Arrival ${localTime(arrival)}`"
          :hover-minute="hoverMinute"
          @hover="hoverMinute = $event"
        />
        <TurbulenceProfile
          class="mt-1"
          :points="analysis.points"
          :light="thresholds.light"
          :moderate="thresholds.moderate"
          :height="100"
          :hover-minute="hoverMinute"
          @hover="hoverMinute = $event"
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
  type AnalyzeRequest,
  type FlightLookup,
  type Place,
  type RouteAnalysis,
  airportByCode,
  type RouteSchedule,
  type ScheduledFlight,
  type PathKind,
  analyzeFlight,
  fetchSchedule,
  formatDuration,
  type DatedFlight,
  fetchDatedFlight,
  lookupFlight,
  pointAt,
  saveRoute3d
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
  if (a.aircraftType) return `${a.aircraftType} · ${a.aircraftClass}`
  if (a.aircraft?.basis === 'assumed')
    return a.aircraftClass === 'Heavy' ? 'Widebody (assumed)' : 'Narrowbody (assumed)'
  return `${a.aircraftClass} (assumed)`
})
/** Categories this flight actually has (Smooth always), so the breakdown stays short enough for one screen. */
const shownCategories = computed(() =>
  (['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme', 'NoData'] as TurbulenceCategory[]).filter(
    (c) => c === 'Smooth' || (analysis.value?.summary.minutesByCategory[c] ?? 0) > 0
  )
)
/** The verdict pass's tabs: everything fits without scrolling. */
const verdictTabs = [
  { id: 'summary', label: 'Summary' },
  { id: 'bumps', label: 'Bumps' },
  { id: 'calm', label: 'Calm' }
] as const
const verdictTab = ref<(typeof verdictTabs)[number]['id']>('summary')
const categoryCodes: Record<TurbulenceCategory, string> = {
  Smooth: 'SMTH',
  Light: 'LGT',
  Moderate: 'MOD',
  Severe: 'SEV',
  Extreme: 'EXT',
  NoData: 'N/A'
}
const worstCode = computed(() => categoryCodes[analysis.value?.summary.worstCategory ?? 'NoData'])
const worstColor = computed(() => {
  const c = analysis.value?.summary.worstCategory ?? 'NoData'
  return c === 'Smooth' ? '#10324f' : categoryColors[c]
})
const compact = (m: number) =>
  `${Math.floor(m / 60)}h${String(Math.round(m % 60)).padStart(2, '0')}`
/** The pass's right-hand code: when the worst bump comes, or the flight time when there is none. */
const worstAt = computed(() => {
  const a = analysis.value
  if (!a) return { code: '', label: '' }
  return a.summary.worstMinute !== undefined && a.summary.worstCategory !== 'Smooth'
    ? { code: compact(a.summary.worstMinute), label: 'into the flight' }
    : { code: compact(a.durationMinutes), label: 'flight time' }
})
const clockAt = (minute: number) =>
  analysis.value
    ? localTime(new Date(new Date(analysis.value.start).getTime() + minute * 60000).toISOString())
    : ''
/** Back-to-back segments that are all bumpy (or all smooth), as one stretch with its worst category. */
function runs(bumpy: boolean) {
  const rank: TurbulenceCategory[] = ['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme']
  const out: { start: number; end: number; category: TurbulenceCategory }[] = []
  for (const s of analysis.value?.segments ?? []) {
    const isBumpy = rank.indexOf(s.category) > 0
    if (s.category === 'NoData' || isBumpy !== bumpy) continue
    const last = out.at(-1)
    if (last && last.end >= s.startMinute - 0.5) {
      last.end = s.endMinute
      if (rank.indexOf(s.category) > rank.indexOf(last.category)) last.category = s.category
    } else out.push({ start: s.startMinute, end: s.endMinute, category: s.category })
  }
  return out
}
/** Where the bumps come, in flight order. */
const bumpyStretches = computed(() => runs(true).slice(0, 5))
/** Smooth stretches long enough to stretch your legs, longest first; climb and descent don't count. */
const calmWindows = computed(() => {
  const end = (analysis.value?.durationMinutes ?? 0) - 30
  return runs(false)
    .map((w) => ({ ...w, start: Math.max(w.start, 20), end: Math.min(w.end, end) }))
    .filter((w) => w.end - w.start >= 20)
    .sort((x, y) => y.end - y.start - (x.end - x.start))
    .slice(0, 4)
})
/** The minute being read on the timeline (hover or drag), mirrored on the map. */
const hoverMinute = ref<number>()
const hoverPoint = computed(() =>
  hoverMinute.value === undefined || !analysis.value
    ? undefined
    : pointAt(analysis.value.points, hoverMinute.value)
)
watch(analysis, () => (hoverMinute.value = undefined))
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
const pathOptions = computed(() => analysis.value?.pathOptions ?? [])
/** The Flight tab's flight on the chosen day, from the timetable. */
const dated = ref<DatedFlight>()
const operatingDaysLabel = computed(() =>
  (dated.value?.operatingDays ?? [])
    .map((d) =>
      new Date(d + 'T12:00').toLocaleDateString([], {
        weekday: 'short',
        month: 'short',
        day: 'numeric'
      })
    )
    .join(', ')
)
/** The path the shown analysis follows, as the picker names it. */
const pathLabel = computed(
  () => pathOptions.value.find((o) => o.kind === analysis.value?.path?.kind)?.label
)
/** The path the person picked; the best available one when unset or unavailable for the flight. */
const pathWanted = ref<PathKind>()
const timetableDayLabel = computed(() =>
  schedule.value?.timetableDay
    ? new Date(schedule.value.timetableDay + 'T12:00').toLocaleDateString([], {
        weekday: 'short',
        month: 'short',
        day: 'numeric'
      })
    : ''
)
/** Published times are local at each airport; ADS-B-only flights show their takeoff and landing in this browser's time. */
function flightTimes(f: ScheduledFlight) {
  if (f.departureLocal && f.arrivalLocal)
    return `${f.departureLocal} → ${f.arrivalLocal}${f.arrivalDayOffset > 0 ? ` +${f.arrivalDayOffset}` : ''}`
  return `takeoff ${localTime(f.takeoff)} → ${localTime(f.landing)}`
}

function choosePath(kind: PathKind) {
  if (kind === analysis.value?.path?.kind && kind === pathWanted.value) return
  pathWanted.value = kind
  if (mode.value === 'route' && analyzedOrigin.value) void pickFlight(selectedFlight.value)
  else void analyze()
}

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
/** Opens the live map's 3D view with this route drawn at its altitudes, colored by its forecast. */
function explore3d() {
  const a = analysis.value
  if (!a) return
  const ends = [analyzedOrigin.value?.code, analyzedDestination.value?.code].filter(Boolean)
  const flight =
    selectedFlight.value?.flightNumber ?? selectedFlight.value?.callsign ?? lookup.value?.callsign
  saveRoute3d({
    label: [
      ends.join(' → '),
      flight,
      `departs ${new Date(a.start).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit' })}`
    ]
      .filter(Boolean)
      .join(' · '),
    path: a.path?.description,
    start: a.start,
    points: a.points
  })
  router.push('/map?view=3d&route3d=1')
}

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
      // The timetable for the chosen day first: flight numbers are reused, and the live databases only know the
      // route a callsign flew last (DL286 was once Tokyo to Saipan).
      const q = flightQuery.value.trim().toUpperCase()
      // The live lookup (about 3 s) only adds the aircraft's position, so it doesn't hold up a timetabled flight.
      const liveLookup = lookupFlight(q).catch(() => undefined)
      const found = await fetchDatedFlight(q, departureLocal.value.slice(0, 10))
      // The server resolves both airports with the flight; looked up here only if it couldn't.
      const ends = found
        ? await Promise.all([
            found.originPlace ?? airportByCode(found.origin),
            found.destinationPlace ?? airportByCode(found.destination)
          ])
        : undefined
      dated.value = ends?.[0] && ends[1] ? found : undefined
      if (dated.value && ends?.[0] && ends[1]) {
        const d = dated.value
        const resolved: FlightLookup = {
          query: q,
          callsign: d.callsign ?? d.flightNumber,
          route: {
            callsign: d.callsign ?? d.flightNumber,
            iataCallsign: d.flightNumber,
            airline: d.airline,
            origin: ends[0],
            destination: ends[1]
          }
        }
        lookup.value = resolved
        void liveLookup.then((live) => {
          const sameRoute =
            live?.route?.origin.code === d.origin && live.route.destination.code === d.destination
          if (sameRoute && live?.live && lookup.value === resolved)
            lookup.value = { ...resolved, live: live.live }
        })
        // Analyze from just after the scheduled gate time, as the Route tab does.
        departureLocal.value = toLocalInput(new Date(new Date(d.departure).getTime() + 20 * 60000))
      } else lookup.value = (await liveLookup) ?? { query: q }
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
      dated.value = undefined
      if (!from || !to) {
        error.value = 'Pick both airports.'
        return
      }
      const day = departureLocal.value.slice(0, 10)
      shown = `route:${from.code}-${to.code}:${day}`
      router.replace({ query: { from: from.code, to: to.code, day } })
      selectedFlight.value = undefined
    }
    analysis.value = await requestAnalysis({
      origin: from!,
      destination: to!,
      departure: new Date(departureLocal.value).toISOString(),
      callsign: mode.value === 'flight' ? lookup.value?.callsign : undefined,
      flightNumber: mode.value === 'flight' ? dated.value?.flightNumber : undefined,
      day: departureLocal.value.slice(0, 10),
      path: pathWanted.value
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
    const result = await fetchSchedule(from, to, start, end, departureLocal.value.slice(0, 10))
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
    : (flights.find((x) => new Date(x.departure).getTime() >= chosen - 30 * 60000) ?? flights[0])
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
    const result = await requestAnalysis(
      f
        ? {
            origin: from,
            destination: to,
            departure: f.takeoff,
            callsign: f.callsign,
            flown: f.hex && f.flownTakeoff ? { hex: f.hex, takeoff: f.flownTakeoff } : undefined,
            flightNumber: f.flightNumber,
            day: departureLocal.value.slice(0, 10),
            path: pathWanted.value
          }
        : {
            origin: from,
            destination: to,
            departure: departureIso.value,
            day: departureLocal.value.slice(0, 10),
            path: pathWanted.value
          }
    )
    if (selectedFlight.value === f) analysis.value = result
    scheduleRetry()
  } catch (e) {
    error.value = `Analysis failed: ${e}`
  } finally {
    loading.value = false
  }
}

/** The last analysis asked for, so a refresh can ask again without touching the page's inputs. */
let lastRequest: AnalyzeRequest | undefined
let requestSeq = 0
function requestAnalysis(request: AnalyzeRequest): Promise<RouteAnalysis> {
  lastRequest = request
  requestSeq++
  pendingRetries = 0
  return analyzeFlight(request)
}

/**
 * The forecast shows at once; past tracks (two weeks of history on a cold server) and, right after a server update,
 * the forecast itself fill in afterwards. Ask again quietly until they have: every 3 s for tracks (about 90 s at most),
 * every minute for data.
 */
function scheduleRetry() {
  window.clearTimeout(retryTimer)
  const dataLoading = !!analysis.value?.dataSources.loading?.length
  const tracksPending = !!analysis.value?.pathOptions?.some((o) => o.pending)
  pendingRetries = tracksPending ? pendingRetries + 1 : 0
  const retryTracks = tracksPending && pendingRetries <= 30
  if (!dataLoading && !retryTracks) return
  retryTimer = window.setTimeout(() => void refresh(), retryTracks ? 3000 : 60000)
}

async function refresh() {
  const request = lastRequest
  const seq = requestSeq
  if (!request) return
  try {
    const result = await analyzeFlight({ ...request, path: pathWanted.value })
    // Dropped if the person has asked for something else meanwhile.
    if (seq !== requestSeq) return
    analysis.value = result
    scheduleRetry()
  } catch {
    if (seq === requestSeq) scheduleRetry()
  }
}
let pendingRetries = 0

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
  /* At least one screen; the verdict row grows rather than clip or scroll when its panel needs more room. */
  .analyze-page.has-result {
    min-height: calc(100vh - 72px);
    display: grid;
    grid-template-columns: minmax(320px, 26%) 1fr;
    grid-template-rows: auto auto 1fr auto;
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
/* The native date-time control is a couple of pixels taller than a text input: match the airport fields. */
.form-row .departure :deep(input) {
  height: 24px;
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
  font-size: 17px;
  font-weight: 500;
  line-height: 1.3;
}
/* The verdict pass: a stub with the worst category and when, torn along a perforation from the tabs. */
.vpass {
  --notch: 12px;
  --navy: #10324f;
  display: flex;
  flex-direction: column;
  min-width: 0;
  filter: drop-shadow(0 10px 18px rgba(8, 25, 40, 0.28))
    drop-shadow(0 2px 3px rgba(8, 25, 40, 0.18));
}
.vp-stub,
.vp-main {
  background: white;
  color: #0f172a;
}
.vp-stub {
  border-radius: 12px 12px 0 0;
  padding: 12px 18px 12px;
  mask:
    radial-gradient(circle var(--notch) at 0 100%, #0000 98%, #000) left / 51% 100% no-repeat,
    radial-gradient(circle var(--notch) at 100% 100%, #0000 98%, #000) right / 51% 100% no-repeat;
}
.vp-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-radius: 0 0 12px 12px;
  padding: 10px 14px 14px;
  mask:
    radial-gradient(circle var(--notch) at 0 0, #0000 98%, #000) left / 51% 100% no-repeat,
    radial-gradient(circle var(--notch) at 100% 0, #0000 98%, #000) right / 51% 100% no-repeat;
}
.vp-perf {
  margin: 0 calc(var(--notch) + 4px);
  border-top: 2px dashed rgba(16, 50, 79, 0.22);
  position: relative;
  z-index: 1;
}
.vp-ends {
  display: flex;
  align-items: flex-end;
  margin: 2px 0 8px;
}
.vp-code {
  font-size: 40px;
  font-weight: 800;
  line-height: 1;
  letter-spacing: 0.01em;
  color: var(--navy);
}
.vp-sub {
  font-size: 12px;
  color: #475569;
  margin-top: 2px;
}
.vp-right {
  text-align: right;
}
.vp-arc {
  flex: 1;
  margin: 0 14px 20px;
  border-top: 2px dashed rgba(91, 143, 178, 0.6);
  position: relative;
}
.vp-arc .v-icon {
  position: absolute;
  top: -12px;
  left: 50%;
  transform: translateX(-50%) rotate(90deg);
  font-size: 20px;
  color: var(--navy);
  background: white;
  padding: 0 4px;
  box-sizing: content-box;
}
.vp-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 2px solid var(--navy);
}
.vp-tabs button {
  flex: 1;
  border: 0;
  padding: 7px 0 6px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #475569;
  border-radius: 8px 8px 0 0;
  background: #eef2f6;
  box-shadow: inset 0 -3px 4px -3px rgba(16, 50, 79, 0.25);
  cursor: pointer;
}
.vp-tabs button.on {
  background: var(--navy);
  color: white;
  box-shadow: 0 -3px 8px rgba(16, 50, 79, 0.25);
}
/* Every pane sits in the same cell, so the pass keeps the tallest pane's height and the map never jumps. */
.vp-panes {
  display: grid;
}
.vp-pane {
  grid-area: 1 / 1;
  padding-top: 12px;
}
.vp-pane.off {
  visibility: hidden;
}
.vp-lbl {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #475569;
  margin-bottom: 6px;
}
/* Route at a glance: a thin frosted track with soft, rounded category pills. */
.vp-glance {
  position: relative;
  height: 10px;
  border-radius: 5px;
  background: rgba(16, 50, 79, 0.07);
  box-shadow: inset 0 1px 2px rgba(16, 50, 79, 0.12);
}
.vp-seg {
  position: absolute;
  top: 2px;
  bottom: 2px;
  border-radius: 3px;
  opacity: 0.72;
  border-left: 1px solid white;
}
.ring.vp-worst {
  position: absolute;
  top: -3px;
  width: 16px;
  height: 16px;
  margin-left: -8px;
  background: white;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
}
.vp-cursor {
  position: absolute;
  top: -4px;
  bottom: -4px;
  width: 2px;
  margin-left: -1px;
  background: var(--navy);
  border-radius: 1px;
}
.vp-glance-ends {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #475569;
  margin-top: 5px;
}
.vp-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 14px;
  margin-top: 12px;
}
.vp-fields span {
  display: block;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #475569;
}
.vp-fields b {
  font-size: 17px;
  font-weight: 700;
  color: var(--navy);
}
.vp-acts {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}
.vp-acts .v-btn {
  text-transform: none;
  letter-spacing: 0;
  font-weight: 600;
  border-radius: 10px;
}
.vp-acts .v-btn:first-child {
  flex: 1;
}
.vp-cat {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  padding: 4px 0;
}
.vp-track {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: rgba(16, 50, 79, 0.07);
  overflow: hidden;
}
.vp-track i {
  display: block;
  height: 100%;
  border-radius: 3px;
  opacity: 0.8;
}
.vp-pc {
  width: 34px;
  text-align: right;
  font-size: 12px;
}
.vp-stretch {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 6px 8px;
  margin: 0 -8px;
  width: calc(100% + 16px);
  border: 0;
  background: none;
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  color: #0f172a;
}
.vp-stretch:hover,
.vp-stretch.on {
  background: rgba(16, 50, 79, 0.06);
}
.vp-foot {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed rgba(16, 50, 79, 0.18);
  font-size: 11.5px;
  line-height: 1.45;
}
.vp-note {
  display: flex;
  gap: 4px;
  color: #0b4f8a;
  margin-bottom: 4px;
}
.vp-note .v-icon {
  margin-top: 1px;
}
.tnum {
  font-variant-numeric: tabular-nums;
}
.ring {
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 2.5px solid #dc2626;
  border-radius: 50%;
  vertical-align: -1px;
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
  font-size: 12px;
}
.note {
  font-size: 13px;
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
.path-row {
  display: flex;
  align-items: stretch;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 2px;
}
.path-title {
  align-self: center;
  flex: none;
  min-width: 40px;
}
.pchip {
  flex: none;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  max-width: 260px;
  padding: 5px 12px;
  border: 1.5px solid rgba(16, 50, 79, 0.25);
  border-radius: 10px;
  background: white;
  color: #10324f;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.pchip .pnote {
  font-size: 11.5px;
  color: #64748b;
  line-height: 1.25;
}
.pchip.active {
  background: #10324f;
  border-color: #10324f;
  color: white;
}
.pchip.active .pnote {
  color: rgba(255, 255, 255, 0.8);
}
.pchip:disabled {
  cursor: default;
  border-style: dashed;
  background: #f1f5f9;
  color: #94a3b8;
}
.pchip:disabled .pnote {
  color: #94a3b8;
}
@media (max-width: 600px) {
  /* Every path stays in view on a phone: two to a row instead of a sideways scroll. */
  .path-row {
    flex-wrap: wrap;
    overflow-x: visible;
  }
  .path-title {
    width: 100%;
  }
  .pchip {
    flex: 1 1 140px;
    max-width: none;
  }
}
.path-tag {
  text-transform: none;
  letter-spacing: normal;
  font-weight: 500;
}
</style>
