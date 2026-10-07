<template>
  <div class="follow-page">
    <v-card v-if="!hex" class="pa-4">
      <div class="text-h6 mb-2">Follow a flight live</div>
      <v-row density="compact" align="center">
        <v-col cols="12" md="5">
          <v-text-field
            v-model="query"
            label="Flight number or callsign of a flight in the air (UA1517)"
            variant="outlined"
            density="comfortable"
            hide-details
            @keyup.enter="find"
          />
        </v-col>
        <v-col cols="12" md="2">
          <v-btn color="primary" size="large" :loading="finding" @click="find">Follow</v-btn>
        </v-col>
      </v-row>
      <v-alert v-if="error" type="warning" variant="tonal" density="compact" class="mt-3">{{ error }}</v-alert>
      <div class="text-body-2 mt-3 muted">Or click any plane on the live map and choose "Follow live".</div>
    </v-card>

    <v-row v-else density="compact" class="fill">
      <v-col cols="12" md="4">
        <v-card class="pa-4 fill-height">
          <div class="d-flex align-center justify-space-between">
            <div class="text-h5 font-weight-bold">{{ a?.callsign ?? hex }}</div>
            <v-chip :color="connected ? 'green' : 'grey'" size="small" variant="flat">
              <v-icon start size="10">mdi-circle</v-icon>
              {{ connected ? `Live · ${secondsAgo}s ago` : 'Connecting…' }}
            </v-chip>
          </div>
          <div class="muted mb-3">
            {{ a?.typeCode ?? '' }} {{ a?.registration ?? '' }}
            <template v-if="update?.route">
              · {{ update.route.origin.code }} → {{ update.route.destination.code }}
            </template>
          </div>

          <v-alert v-if="update?.status === 'lost'" type="info" variant="tonal" density="compact">
            This flight isn't being tracked right now: it may have landed or left ADS-B coverage.
          </v-alert>

          <template v-if="a">
            <div class="now-box" :style="{ borderColor: categoryColors[nowCategory] }">
              <div class="text-overline">Turbulence right now</div>
              <div class="text-h4 font-weight-bold" :style="{ color: categoryColors[nowCategory] }">
                {{ categoryLabels[nowCategory] }}
              </div>
              <div class="muted" v-if="update?.edrNow !== undefined">EDR {{ update.edrNow.toFixed(2) }}</div>
            </div>
            <div class="text-h6 my-3">{{ update?.upcoming?.headline }}</div>
            <div class="stats">
              <div><span>Altitude</span>{{ flightLevel(a.altitudeFt) }}</div>
              <div><span>Ground speed</span>{{ Math.round(a.groundSpeedKts ?? 0) }} kt</div>
              <div><span>Vertical</span>{{ a.verticalRateFpm ?? 0 }} ft/min</div>
              <div><span>Aircraft class</span>{{ update?.aircraftClass }}</div>
              <div v-if="update?.ahead">
                <span>{{ update.usingRoute ? 'To destination' : 'Looking ahead' }}</span>
                {{ formatDuration(update.ahead.durationMinutes) }}
              </div>
              <div v-if="update?.upcoming">
                <span>Worst next hour</span>
                <b :style="{ color: categoryColors[update.upcoming.worstNextHour] }">
                  {{ categoryLabels[update.upcoming.worstNextHour] }}
                </b>
              </div>
            </div>
            <div class="mt-4" v-if="update?.ahead">
              <div class="text-subtitle-2 mb-1">
                {{ update.usingRoute ? 'Rest of the flight' : 'Next 90 minutes along the current track' }}
              </div>
              <TimelineBar
                :segments="update.ahead.segments"
                :now-minute="0"
                start-label="Now"
                :end-label="update.usingRoute ? `Arrive ~${arrivalTime}` : `+${formatDuration(update.ahead.durationMinutes)}`"
              />
            </div>
            <div class="mt-4 d-flex ga-2">
              <v-btn variant="tonal" size="small" @click="stop">Follow another flight</v-btn>
            </div>
          </template>
        </v-card>
      </v-col>
      <v-col cols="12" md="8">
        <v-card class="pa-2 map-card">
          <RouteMap
            :points="update?.ahead?.points ?? []"
            :destination="update?.usingRoute ? update.route?.destination : undefined"
            :aircraft="aircraftView"
            :trail="trail"
            :overlay="overlay"
            follow
          />
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import RouteMap from '@/components/analysis/RouteMap.vue'
import TimelineBar from '@/components/analysis/TimelineBar.vue'
import { type FlightRoute, type RouteAnalysis, formatDuration, lookupFlight } from '@/api/flights'
import {
  type AircraftClass,
  type AircraftView,
  type TurbulenceCategory,
  apiBase,
  categoryColors,
  fetchStatus,
  categoryLabels,
  flightLevel
} from '@/api/turbulence'

interface LiveAircraft {
  hex: string
  callsign?: string
  registration?: string
  typeCode?: string
  lat: number
  lon: number
  altitudeFt?: number
  onGround: boolean
  groundSpeedKts?: number
  trackDeg?: number
  verticalRateFpm?: number
}

interface LiveFlightUpdate {
  time: string
  status: 'tracking' | 'lost' | 'on-ground' | 'error'
  aircraft?: LiveAircraft
  aircraftClass: AircraftClass
  edrNow?: number
  route?: FlightRoute
  usingRoute: boolean
  upcoming?: {
    headline: string
    nowCategory: TurbulenceCategory
    worstNextHour: TurbulenceCategory
    nextBumpInMinutes?: number
  }
  ahead?: RouteAnalysis
}

const route = useRoute()
const router = useRouter()
const hex = computed(() => (route.params.hex as string | undefined) || undefined)
const query = ref('')
const finding = ref(false)
const error = ref<string>()
const update = ref<LiveFlightUpdate>()
const trail = ref<[number, number][]>([])
const connected = ref(false)
const lastUpdate = ref(0)
const tick = ref(Date.now())
let source: EventSource | undefined
let ticker: number | undefined

const a = computed(() => update.value?.aircraft)
const nowcastId = ref<string>()
const overlay = computed(() => {
  const alt = a.value?.altitudeFt
  if (!nowcastId.value || alt === undefined) return undefined
  // Snap to 1,000 ft so the tiles don't reload on every small altitude change.
  return { frameId: nowcastId.value, levelFt: Math.round(alt / 1000) * 1000 }
})
const nowCategory = computed<TurbulenceCategory>(() => update.value?.upcoming?.nowCategory ?? 'NoData')
const secondsAgo = computed(() => Math.max(0, Math.round((tick.value - lastUpdate.value) / 1000)))
const aircraftView = computed<AircraftView | undefined>(() =>
  a.value
    ? {
        ...a.value,
        aircraftClass: update.value!.aircraftClass,
        edr: update.value!.edrNow,
        category: nowCategory.value
      }
    : undefined
)
const arrivalTime = computed(() => {
  const d = update.value?.ahead?.durationMinutes
  return d === undefined
    ? ''
    : new Date(Date.now() + d * 60000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
})

function connect(id: string) {
  disconnect()
  trail.value = []
  update.value = undefined
  source = new EventSource(`${apiBase()}/api/live/flights/${encodeURIComponent(id)}/stream`)
  source.onopen = () => (connected.value = true)
  source.onerror = () => (connected.value = false)
  source.onmessage = (event) => {
    const u = JSON.parse(event.data) as LiveFlightUpdate
    if (u.status === 'error') return
    update.value = u
    lastUpdate.value = Date.now()
    connected.value = true
    if (u.aircraft) {
      const last = trail.value.at(-1)
      if (!last || last[0] !== u.aircraft.lat || last[1] !== u.aircraft.lon) {
        trail.value = [...trail.value, [u.aircraft.lat, u.aircraft.lon]]
      }
    }
  }
}

function disconnect() {
  source?.close()
  source = undefined
  connected.value = false
}

async function find() {
  error.value = undefined
  finding.value = true
  try {
    const result = await lookupFlight(query.value.trim())
    if (result.live) router.push(`/follow/${result.live.hex}`)
    else error.value = `${query.value} isn't in the air right now (or not visible to ADS-B receivers).`
  } finally {
    finding.value = false
  }
}

function stop() {
  disconnect()
  router.push('/follow')
}

watch(hex, (id) => (id ? connect(id) : disconnect()))
onMounted(() => {
  if (hex.value) connect(hex.value)
  fetchStatus()
    .then((s) => (nowcastId.value = s.nowcast?.id))
    .catch(() => undefined)
  ticker = window.setInterval(() => (tick.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  disconnect()
  window.clearInterval(ticker)
})
</script>

<style scoped>
.follow-page {
  width: 100%;
}
.map-card {
  height: calc(100vh - 100px);
  min-height: 420px;
}
.now-box {
  border-left: 6px solid;
  padding: 6px 12px;
  background: #f8fafc;
  border-radius: 6px;
}
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 12px;
  font-size: 14px;
}
.stats span {
  display: block;
  font-size: 11px;
  color: #64748b;
  text-transform: uppercase;
}
.muted {
  color: #64748b;
}
</style>
