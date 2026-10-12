<template>
  <div class="fly-page">
    <v-card v-if="loaded && (adding || flights.length === 0)" class="panel pa-4">
      <AddFlight :can-cancel="flights.length > 0" @saved="onSaved" @cancel="adding = false" />
    </v-card>

    <template v-else-if="flight && est">
      <v-card class="panel pa-4">
        <div v-if="flights.length > 1" class="picker mb-2">
          <v-chip
            v-for="f in flights"
            :key="f.id"
            size="small"
            :variant="f.id === flight.id ? 'flat' : 'outlined'"
            color="#10324f"
            @click="select(f.id)"
          >
            {{ f.label }}
          </v-chip>
        </div>
        <div class="eyebrow">Your flight</div>
        <div class="title">
          {{ flight.label }}
          <span v-if="flight.label !== routeLabel" class="muted"> · {{ routeLabel }}</span>
        </div>
        <div class="muted">
          Departs {{ clock(flight.departure) }} ·
          {{ flight.aircraftType ? `${flight.aircraftType} · ` : ''
          }}{{ flight.aircraftClass }} aircraft
        </div>
        <div class="chips mt-2">
          <v-chip
            v-if="packChip"
            size="small"
            :color="packChip.color"
            variant="flat"
            :prepend-icon="packChip.icon"
          >
            {{ packChip.text }}
          </v-chip>
          <v-chip
            v-if="online !== undefined"
            size="small"
            :color="online ? 'green-darken-2' : 'blue-grey'"
            variant="tonal"
            :prepend-icon="online ? 'mdi-wifi' : 'mdi-airplane'"
          >
            {{ online ? 'Online' : 'Offline' }}
          </v-chip>
          <v-chip
            v-if="est.phase === 'air'"
            size="small"
            variant="tonal"
            prepend-icon="mdi-crosshairs-gps"
          >
            {{ positionText }}
          </v-chip>
        </div>
      </v-card>

      <v-card class="panel pa-4">
        <template v-if="!pack">
          <div class="headline">{{ noPackText }}</div>
          <v-progress-linear
            v-if="progress && progress.total > 0"
            class="mt-3"
            color="#10324f"
            height="6"
            rounded
            :model-value="(100 * progress.done) / progress.total"
          />
        </template>
        <template v-else-if="est.phase === 'gate'">
          <div class="eyebrow">At the gate</div>
          <div class="headline">
            Takeoff about {{ clock(est.takeoff) }}, {{ relative(est.takeoff) }}.
          </div>
          <div v-if="analysis" class="verdict mt-2">{{ analysis.summary.verdict }}</div>
        </template>
        <template v-else-if="est.phase === 'air'">
          <div class="eyebrow">In the air</div>
          <div class="headline">{{ next?.headline }}</div>
          <div class="muted mt-1">{{ airLine }}</div>
        </template>
        <template v-else>
          <div class="eyebrow">Landed</div>
          <div class="headline">Landed about {{ clock(est.landing) }}.</div>
        </template>

        <div class="actions mt-3">
          <v-btn
            v-if="est.basis === 'schedule' && est.phase !== 'landed'"
            color="#10324f"
            prepend-icon="mdi-airplane-takeoff"
            @click="tookOff"
          >
            We've taken off
          </v-btn>
          <v-btn v-if="est.basis === 'tap'" variant="text" @click="undoTakeoff">
            Not yet: undo takeoff
          </v-btn>
          <v-btn
            v-if="est.phase !== 'landed'"
            :variant="gpsOn ? 'tonal' : 'outlined'"
            :prepend-icon="gpsOn ? 'mdi-crosshairs-gps' : 'mdi-crosshairs'"
            @click="gpsOn ? stopGps() : startGps()"
          >
            {{ gpsOn ? 'Using GPS' : 'Use GPS' }}
          </v-btn>
        </div>
        <div v-if="est.basis === 'schedule' && est.phase === 'air'" class="muted small mt-2">
          Assuming takeoff {{ taxiOutMinutes }} minutes after the scheduled departure. Tap "We've
          taken off" when the wheels leave the ground, or turn on GPS, for a closer estimate.
        </div>
        <v-alert v-if="gpsError" type="info" variant="tonal" density="compact" class="mt-2">{{
          gpsError
        }}</v-alert>
      </v-card>

      <v-card v-if="analysis" class="panel px-4 py-3">
        <div class="eyebrow mb-1">Turbulence along the flight</div>
        <TimelineBar
          :segments="analysis.segments"
          :start-label="`Takeoff ${clock(est.takeoff)}`"
          :end-label="`Landing ${clock(est.landing)}`"
          :now-minute="nowMinute"
          :uncertainty-minutes="bandMinutes"
        />
        <TurbulenceProfile
          class="mt-1"
          :points="analysis.points"
          :light="thresholds.light"
          :moderate="thresholds.moderate"
          :height="110"
          :now-minute="nowMinute"
          :uncertainty-minutes="bandMinutes"
        />
        <div class="mt-2">
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
        <div class="muted small mt-3">
          From the pack on this phone, downloaded
          {{ flight.checkedAt ? ago(flight.checkedAt) : 'earlier' }}: GTG forecast issued
          {{ utc(flight.manifest?.forecastIssued) }}
          <template v-if="flight.manifest?.nowcastValid">
            · nowcast {{ utc(flight.manifest.nowcastValid) }}</template
          >
          <template v-if="flight.manifest?.globalIssued">
            · outside the US: WAFS + NOAA GFS, cycle {{ utc(flight.manifest.globalIssued) }}
          </template>
        </div>
      </v-card>

      <v-card class="panel pa-3">
        <div class="actions">
          <v-btn variant="outlined" prepend-icon="mdi-refresh" :loading="syncing" @click="syncNow"
            >Update now</v-btn
          >
          <v-btn variant="text" prepend-icon="mdi-plus" @click="adding = true">Add a flight</v-btn>
          <v-btn
            v-if="!confirmRemove"
            variant="text"
            prepend-icon="mdi-delete-outline"
            @click="confirmRemove = true"
          >
            Remove
          </v-btn>
          <template v-else>
            <v-btn color="red-darken-2" variant="flat" @click="remove"
              >Remove {{ flight.label }}</v-btn
            >
            <v-btn variant="text" @click="confirmRemove = false">Keep</v-btn>
          </template>
        </div>
        <div v-if="syncError" class="muted small mt-2">Last update failed: {{ syncError }}</div>
        <div v-if="showInstallTip" class="muted small mt-2">
          On iPhone, add Turbol to your Home Screen (Share, then Add to Home Screen) so it opens
          offline and iOS keeps your flight's data.
        </div>
      </v-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AddFlight from '@/components/fly/AddFlight.vue'
import TimelineBar from '@/components/analysis/TimelineBar.vue'
import TurbulenceProfile from '@/components/analysis/TurbulenceProfile.vue'
import { type RouteAnalysis, formatDuration } from '@/api/flights'
import {
  type TurbulenceCategory,
  categoryColors,
  categoryLabels,
  flightLevel,
  formatUtc
} from '@/api/turbulence'
import { type Upcoming, analyzeFlight, defaultThresholds, upcoming } from '@/fly/analyzer'
import { idbStore } from '@/fly/db'
import { type GpsFix, type SavedFlight, loadPack } from '@/fly/flights'
import { setFlying } from '@/fly/flying'
import { greatCircleKm } from '@/fly/geo'
import { type PackData, packSource, sample } from '@/fly/pack'
import { estimate, taxiOutMinutes, withFix } from '@/fly/position'
import { type SyncProgress, ping, removeFlight, syncFlight } from '@/fly/sync'

const route = useRoute()
const router = useRouter()

const flights = ref<SavedFlight[]>([])
const loaded = ref(false)
const selectedId = ref<string>()
const flight = computed(() => flights.value.find((f) => f.id === selectedId.value))
const pack = shallowRef<PackData>()
const now = ref(Date.now())
const online = ref<boolean>()
const syncing = ref(false)
const progress = ref<SyncProgress>()
const syncError = ref<string>()
const adding = ref(false)
const confirmRemove = ref(false)
const gpsOn = ref(false)
const gpsError = ref<string>()

const est = computed(() => (flight.value ? estimate(flight.value, now.value) : undefined))
const routeLabel = computed(() =>
  flight.value ? `${flight.value.origin.code} → ${flight.value.destination.code}` : ''
)
const allThresholds = computed(() => flight.value?.manifest?.thresholds ?? defaultThresholds)
const thresholds = computed(() => allThresholds.value[flight.value?.aircraftClass ?? 'Medium'])

/** When the plan's clock started, to the minute, so the analysis holds still between GPS fixes. */
const anchor = computed(() =>
  est.value ? Math.round(est.value.planStart / 60000) * 60000 : undefined
)
/** Where the plane is on the analysis' clock (planned minutes), while in the air. */
const nowMinute = computed(() => (est.value?.phase === 'air' ? est.value.plannedMinute : undefined))
/** How unsure the position is, in the analysis' planned minutes. */
const bandMinutes = computed(() => (est.value ? est.value.uncertaintyMinutes / est.value.pace : 0))

/** The whole flight, gate to gate, from the takeoff the estimate has. */
const analysis = computed<RouteAnalysis | undefined>(() => {
  const f = flight.value
  const e = est.value
  if (!f || !e || !pack.value) return undefined
  return analyzeFlight(
    packSource(pack.value),
    f.origin.lat,
    f.origin.lon,
    f.destination.lat,
    f.destination.lon,
    anchor.value!,
    f.cruiseFt,
    f.aircraftClass,
    { thresholds: allThresholds.value, now: now.value }
  )
})

/** The next hour from where the plane is now, in passenger terms. */
const next = computed<Upcoming | undefined>(() => {
  const a = analysis.value
  const e = est.value
  const f = flight.value
  const offset = nowMinute.value
  if (!a || !e || !f || !pack.value || offset === undefined) return undefined
  // Real minutes from now: a tailwind brings what's ahead sooner than the plan says.
  const ahead = a.points
    .filter((p) => p.minutesFromStart >= offset)
    .map((p) => ({ ...p, minutesFromStart: (p.minutesFromStart - offset) * e.pace }))
  const edrNow = sample(pack.value, e.lat, e.lon, e.altitudeFt, now.value).edr
  return upcoming({ ...a, points: ahead }, edrNow, f.aircraftClass, allThresholds.value)
})

const shownCategories = computed(() =>
  (['Smooth', 'Light', 'Moderate', 'Severe', 'Extreme', 'NoData'] as TurbulenceCategory[]).filter(
    (c) => c === 'Smooth' || (analysis.value?.summary.minutesByCategory[c] ?? 0) > 0
  )
)

const packChip = computed(() => {
  const f = flight.value
  if (!f) return undefined
  if (syncing.value && progress.value && progress.value.done < progress.value.total)
    return {
      text: `Downloading ${progress.value.done} of ${progress.value.total}`,
      color: 'blue-darken-2',
      icon: 'mdi-download'
    }
  if (f.manifest && !f.pending)
    return { text: 'Ready offline', color: 'green-darken-2', icon: 'mdi-check-circle' }
  if (f.manifest)
    return {
      text: 'Ready offline, update paused',
      color: 'green-darken-2',
      icon: 'mdi-check-circle'
    }
  return { text: 'Not downloaded yet', color: 'red-darken-2', icon: 'mdi-alert-circle' }
})

const noPackText = computed(() => {
  const f = flight.value
  if (f?.manifest && f.manifest.frames.length === 0)
    return 'No forecast reaches this flight yet. Turbol downloads it when you open the app closer to the day.'
  if (syncing.value) return 'Downloading the turbulence along your flight…'
  return online.value === false
    ? "This flight's turbulence isn't on the phone yet. Connect to download it."
    : "This flight's turbulence isn't on the phone yet."
})

const positionText = computed(() => {
  const e = est.value
  if (!e) return ''
  const pm = e.uncertaintyMinutes >= 1 ? ` ±${Math.round(e.uncertaintyMinutes)} min` : ''
  switch (e.basis) {
    case 'gps':
      return `GPS ${Math.round(e.fixAgeMinutes ?? 0)} min ago${pm}`
    case 'gps-takeoff':
      return `GPS takeoff${pm}`
    case 'tap':
      return `Your takeoff time${pm}`
    default:
      return `Schedule${pm}`
  }
})

/** The altitude the profile puts the plane at, no more precise than it can be; nothing on the runway. */
const altitude = computed(() => {
  const ft = est.value?.altitudeFt ?? 0
  return ft >= 18000 ? flightLevel(ft) : ft >= 1000 ? flightLevel(Math.round(ft / 1000) * 1000) : ''
})

/** How far along the flight is, how sure Turbol is of that, and how high. */
const airLine = computed(() => {
  const e = est.value
  if (!e) return ''
  const along = `About ${formatDuration(e.minutesIntoFlight)} into the flight, ${formatDuration(e.minutesToGo)} to go`
  const sure =
    e.uncertaintyMinutes >= 1 ? ` (give or take ${formatDuration(e.uncertaintyMinutes)})` : ''
  return along + sure + (altitude.value ? ` · ${altitude.value}` : '')
})

const showInstallTip = computed(
  () =>
    /iPhone|iPad|iPod/.test(navigator.userAgent) &&
    !(navigator as Navigator & { standalone?: boolean }).standalone
)

const clock = (ms: number) =>
  new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
const utc = (iso?: string) => (iso ? formatUtc(iso) : '–')
function relative(ms: number): string {
  const minutes = (ms - now.value) / 60000
  return minutes >= 0 ? `in ${formatDuration(minutes)}` : `${formatDuration(-minutes)} ago`
}
const ago = (ms: number) => relative(ms)

function replaceFlight(f: SavedFlight) {
  const k = flights.value.findIndex((x) => x.id === f.id)
  if (k >= 0) flights.value.splice(k, 1, f)
  else flights.value.push(f)
}

/** Changes some fields of a flight on screen; a flight removed meanwhile stays removed. */
function patchFlight(id: string, fields: Partial<SavedFlight>) {
  const k = flights.value.findIndex((x) => x.id === id)
  if (k >= 0) flights.value.splice(k, 1, { ...flights.value[k], ...fields })
}

/** Keeps what the phone knows about the takeoff and position, leaving the pack to the download. */
async function savePosition(id: string, fields: Pick<SavedFlight, 'takeoff' | 'airborneFix'>) {
  patchFlight(id, fields)
  await idbStore.updateFlight(id, (f) => ({ ...f, ...fields }))
}

function select(id: string) {
  selectedId.value = id
  confirmRemove.value = false
  router.replace({ query: { flight: id } })
}

/** The flight to show: the one asked for, else the one in the air or leaving soonest. */
function pickDefault(): string | undefined {
  const asked = route.query.flight
  if (typeof asked === 'string' && flights.value.some((f) => f.id === asked)) return asked
  const t = Date.now()
  const score = (f: SavedFlight) => {
    const e = estimate(f, t)
    return e.phase === 'air' ? -1 : e.phase === 'gate' ? e.takeoff - t : 1e15 + t - e.takeoff
  }
  return [...flights.value].sort((a, b) => score(a) - score(b))[0]?.id
}

// The pack in use, decoded, whenever the flight switches to another one.
let packKey: string | undefined
watch(
  () => [flight.value?.id, flight.value?.manifest?.frames.map((f) => f.url).join('|')] as const,
  async ([id, frames]) => {
    const key = `${id}#${frames}`
    if (key === packKey) return
    packKey = key
    const m = flight.value?.manifest
    const data = m && m.frames.length > 0 ? await loadPack(idbStore, m) : undefined
    if (packKey === key) pack.value = data
  },
  { immediate: true }
)

// Hold back app updates while any saved flight is in the air, until an hour after it lands (see src/sw.ts).
const flyingUntil = computed(() => {
  let until: number | undefined
  for (const f of flights.value) {
    const e = estimate(f, now.value)
    if (e.phase === 'air') until = Math.max(until ?? 0, e.landing + 3600_000)
  }
  // To the minute, so the marker isn't rewritten on every tick.
  return until === undefined ? undefined : Math.ceil(until / 60000) * 60000
})
watch(flyingUntil, (until) => setFlying(until), { immediate: true })

let syncTimer: number | undefined
let failures = 0
let lastAttempt = 0

async function syncNow() {
  const f = flight.value
  if (!f || syncing.value) return
  window.clearTimeout(syncTimer)
  syncing.value = true
  lastAttempt = Date.now()
  try {
    online.value = await ping()
    if (!online.value) {
      failures += 1
      return
    }
    const result = await syncFlight(idbStore, f, { onProgress: (p) => (progress.value = p) })
    const { manifest, pending, checkedAt } = result.flight
    patchFlight(f.id, { manifest, pending, checkedAt })
    if (result.status === 'offline') {
      failures += 1
      syncError.value = 'the connection dropped; Turbol picks up where it left off next time.'
    } else {
      failures = 0
      syncError.value = undefined
      navigator.storage?.persist?.().catch(() => undefined)
    }
  } catch (e) {
    failures += 1
    syncError.value = String(e)
  } finally {
    syncing.value = false
    progress.value = undefined
    // Another flight picked meanwhile gets its turn now.
    if (flight.value && flight.value.id !== f.id) void syncNow()
    else scheduleSync()
  }
}

/** Check for a newer pack every few minutes in the air, less often on the ground, sooner after a failed try. */
function scheduleSync() {
  window.clearTimeout(syncTimer)
  if (!flight.value || est.value?.phase === 'landed') return
  const every = est.value?.phase === 'air' ? 3 : 10
  const minutes = failures > 0 ? Math.min(every, 0.5 * 2 ** (failures - 1)) : every
  syncTimer = window.setTimeout(syncNow, minutes * 60000)
}

function onOnline() {
  failures = 0
  syncNow()
}
function onVisible() {
  if (document.visibilityState === 'visible') {
    now.value = Date.now()
    if (Date.now() - lastAttempt > 60000) syncNow()
  }
}

async function onSaved(f: SavedFlight) {
  adding.value = false
  replaceFlight(f)
  select(f.id)
  failures = 0
  await syncNow()
}

async function tookOff() {
  const f = flight.value
  // Move the clock to the tap too, or the estimate (up to a tick behind) would still be at the gate.
  now.value = Date.now()
  if (f)
    await savePosition(f.id, {
      takeoff: { time: now.value, source: 'tap' },
      airborneFix: undefined
    })
}

async function undoTakeoff() {
  const f = flight.value
  if (f) await savePosition(f.id, { takeoff: undefined, airborneFix: undefined })
}

async function remove() {
  const f = flight.value
  if (!f) return
  await removeFlight(idbStore, f.id)
  flights.value = flights.value.filter((x) => x.id !== f.id)
  confirmRemove.value = false
  pack.value = undefined
  packKey = undefined
  selectedId.value = pickDefault()
}

let watchId: number | undefined
/** The last minute of fixes, to work out speed from when the phone doesn't report it. */
let recentFixes: GpsFix[] = []

function startGps() {
  gpsError.value = undefined
  if (!('geolocation' in navigator)) {
    gpsError.value = "This browser can't use the phone's GPS."
    return
  }
  gpsOn.value = true
  try {
    localStorage.setItem('turbol.fly.gps', '1')
  } catch {
    // Private mode: GPS just isn't remembered.
  }
  watchId = navigator.geolocation.watchPosition(onPosition, onGpsError, {
    enableHighAccuracy: true,
    maximumAge: 15000,
    timeout: 120000
  })
}

function stopGps() {
  if (watchId !== undefined) navigator.geolocation.clearWatch(watchId)
  watchId = undefined
  gpsOn.value = false
  try {
    localStorage.removeItem('turbol.fly.gps')
  } catch {
    // ignore
  }
}

function onGpsError(e: GeolocationPositionError) {
  gpsError.value =
    e.code === e.PERMISSION_DENIED
      ? 'GPS is off for this site. Turbol keeps estimating from your takeoff time.'
      : 'No GPS fix yet. Near a window it usually finds one within a few minutes.'
}

async function onPosition(p: GeolocationPosition) {
  gpsError.value = undefined
  const received = Date.now()
  const fix: GpsFix = {
    // When the GPS took the fix; a timestamp on some other clock is no use, so then it's when it arrived.
    time: Math.abs(p.timestamp - received) < 5 * 60000 ? p.timestamp : received,
    lat: p.coords.latitude,
    lon: p.coords.longitude,
    speedMps: p.coords.speed ?? undefined,
    accuracyM: p.coords.accuracy
  }
  // Some phones don't report speed: work it out from the latest fix at least 5 s earlier.
  const from = [...recentFixes].reverse().find((r) => fix.time - r.time >= 5000)
  if (fix.speedMps === undefined && from)
    fix.speedMps =
      (greatCircleKm(from.lat, from.lon, fix.lat, fix.lon) * 1000) / ((fix.time - from.time) / 1000)
  recentFixes = [...recentFixes.filter((r) => fix.time - r.time < 60000), fix]
  const f = flight.value
  // One stored fix every 30 s is plenty for dead reckoning.
  if (!f || (f.airborneFix && fix.time - f.airborneFix.time < 30000)) return
  now.value = received
  const updated = withFix(f, fix)
  if (updated)
    await savePosition(f.id, { takeoff: updated.takeoff, airborneFix: updated.airborneFix })
}

let ticker: number | undefined

onMounted(async () => {
  try {
    flights.value = await idbStore.flights()
  } catch (e) {
    syncError.value = `this browser can't keep flights offline (${e}).`
  }
  selectedId.value = pickDefault()
  loaded.value = true
  ticker = window.setInterval(() => (now.value = Date.now()), 15000)
  window.addEventListener('online', onOnline)
  document.addEventListener('visibilitychange', onVisible)
  let gpsWanted = false
  try {
    gpsWanted = localStorage.getItem('turbol.fly.gps') === '1'
  } catch {
    // ignore
  }
  if (gpsWanted) startGps()
  await syncNow()
})

watch(selectedId, () => {
  failures = 0
  if (loaded.value) syncNow()
})

onBeforeUnmount(() => {
  window.clearInterval(ticker)
  window.clearTimeout(syncTimer)
  window.removeEventListener('online', onOnline)
  document.removeEventListener('visibilitychange', onVisible)
  if (watchId !== undefined) navigator.geolocation.clearWatch(watchId)
})
</script>

<style scoped>
.fly-page {
  width: 100%;
  max-width: 880px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
/* Near-opaque light panels: dark text stays readable over the cloud photo. */
.v-card.panel {
  background: rgba(255, 255, 255, 0.93);
  color: #0f172a;
  backdrop-filter: blur(6px);
  border-radius: 10px;
}
.eyebrow {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #334155;
}
.title {
  font-size: 20px;
  font-weight: 600;
}
.headline {
  font-size: 20px;
  font-weight: 500;
  line-height: 1.35;
}
.verdict {
  font-size: 16px;
}
.chips,
.actions,
.picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
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
.small {
  font-size: 13px;
}
.note {
  font-size: 14px;
}
</style>
