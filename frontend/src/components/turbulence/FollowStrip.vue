<template>
  <div
    class="follow-strip glass-panel"
    :class="{ sheet: mobile, collapsed }"
    role="region"
    aria-label="Followed flight"
  >
    <button
      v-if="mobile"
      class="grab"
      :aria-label="collapsed ? 'Show details' : 'Hide details'"
      @click="collapsed = !collapsed"
    />

    <div class="who">
      <div class="who-head">
        <span class="callsign">{{ title }}</span>
        <span v-if="a?.typeCode" class="type">{{ a.typeCode }}</span>
        <template v-if="mobile && offline">
          <v-btn
            v-if="saved"
            size="small"
            variant="tonal"
            theme="dark"
            class="ml-auto"
            :prepend-icon="offline.icon"
            :to="savedLink"
            >{{ offline.short }}</v-btn
          >
          <v-btn
            v-else
            size="small"
            variant="tonal"
            theme="dark"
            class="ml-auto"
            prepend-icon="mdi-download"
            aria-label="Download for offline"
            :loading="downloading"
            @click="download"
            >Download</v-btn
          >
        </template>
        <v-btn
          v-if="mobile"
          icon="mdi-close"
          size="small"
          variant="text"
          theme="dark"
          :class="{ 'ml-auto': !offline }"
          aria-label="Stop following"
          @click="emit('close')"
        />
      </div>
      <div class="live" :class="{ off: !live }">
        {{
          live
            ? secondsAgo <= 2
              ? 'Live'
              : `Live · ${secondsAgo}s ago`
            : lastUpdate
              ? 'Reconnecting…'
              : 'Connecting…'
        }}
      </div>
      <template v-if="update?.route && update.usingRoute">
        <div class="progress" :aria-label="`${Math.round(progress * 100)}% of the way`">
          <b :style="{ width: `${progress * 100}%` }" />
          <v-icon
            icon="mdi-airplane"
            size="14"
            class="progress-plane"
            :style="{ left: `${progress * 100}%` }"
          />
        </div>
        <div class="ends">
          <span class="code">{{ update.route.origin.code }}</span>
          <span v-if="update.ahead" class="muted"
            >{{ formatDuration(update.ahead.durationMinutes) }} left</span
          >
          <span class="code">{{ update.route.destination.code }}</span>
        </div>
      </template>
      <div v-else-if="a" class="muted mt-1">
        {{ a.registration ?? '' }} heading {{ Math.round(a.trackDeg ?? 0) }}°
      </div>
    </div>

    <template v-if="!collapsed">
      <div v-if="update?.status === 'lost' || update?.status === 'on-ground'" class="lost">
        {{
          update.status === 'on-ground'
            ? 'This flight is on the ground.'
            : "This flight isn't being tracked right now: it may have landed or left ADS-B coverage."
        }}
      </div>

      <template v-else-if="a">
        <div class="now">
          <div class="label">Right now</div>
          <span class="badge" :style="{ '--c': categoryColors[nowCategory] }">
            <span class="dot" />{{ categoryLabels[nowCategory] }}
          </span>
          <div class="muted stats">
            <template v-if="update?.edrNow !== undefined"
              >EDR {{ update.edrNow.toFixed(2) }} ·
            </template>
            {{ flightLevel(a.altitudeFt) }} · {{ Math.round(a.groundSpeedKts ?? 0) }} kt
          </div>
        </div>

        <div class="ahead">
          <div class="headline">{{ update?.upcoming?.headline ?? 'Looking ahead…' }}</div>
          <template v-if="update?.ahead">
            <div class="bar">
              <div
                v-for="(s, i) in update.ahead.segments"
                :key="i"
                class="seg"
                :style="{
                  left: `${(100 * s.startMinute) / aheadMinutes}%`,
                  width: `${Math.max((100 * (s.endMinute - s.startMinute)) / aheadMinutes, 0.5)}%`,
                  background: categoryColors[s.category]
                }"
                :title="`${categoryLabels[s.category]}: ${formatDuration(s.startMinute)} to ${formatDuration(s.endMinute)}`"
              />
            </div>
            <div class="ticks">
              <span>Now</span>
              <span v-if="update.upcoming" class="worst">
                Worst next hour:
                <b :style="{ color: categoryColors[update.upcoming.worstNextHour] }">
                  {{ categoryLabels[update.upcoming.worstNextHour] }}
                </b>
              </span>
              <span>{{ endLabel }}</span>
            </div>
          </template>
        </div>
      </template>
    </template>

    <div v-if="!mobile" class="actions">
      <v-btn
        icon="mdi-close"
        size="x-small"
        variant="tonal"
        theme="dark"
        aria-label="Stop following"
        @click="emit('close')"
      />
      <v-btn
        v-if="a?.callsign"
        size="small"
        variant="tonal"
        theme="dark"
        prepend-icon="mdi-airplane-search"
        :to="`/analyze?q=${encodeURIComponent(a.callsign)}`"
        >Analyze</v-btn
      >
      <template v-if="offline">
        <v-btn
          v-if="saved"
          size="small"
          variant="tonal"
          theme="dark"
          :prepend-icon="offline.icon"
          :to="savedLink"
          >{{ offline.text }}</v-btn
        >
        <v-btn
          v-else
          size="small"
          variant="tonal"
          theme="dark"
          prepend-icon="mdi-download"
          :loading="downloading"
          @click="download"
          >Download for offline</v-btn
        >
      </template>
    </div>
    <div v-if="downloadError" class="download-error">{{ downloadError }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { formatDuration } from '@/api/flights'
import { type LiveFlightUpdate, followFlight } from '@/api/follow'
import { downloads, packStatus } from '@/fly/downloads'
import { flightFromLive, sameFlight } from '@/fly/live'
import {
  type TurbulenceCategory,
  categoryColors,
  categoryLabels,
  flightLevel
} from '@/api/turbulence'

const props = defineProps<{ hex: string; mobile?: boolean }>()
const emit = defineEmits<{ update: [u: LiveFlightUpdate]; close: [] }>()

const update = ref<LiveFlightUpdate>()
const lastUpdate = ref(0)
const tick = ref(Date.now())
const collapsed = ref(false)
let stop: (() => void) | undefined
const ticker = window.setInterval(() => (tick.value = Date.now()), 1000)

const a = computed(() => update.value?.aircraft)
const title = computed(
  () => update.value?.route?.iataCallsign ?? a.value?.callsign ?? props.hex.toUpperCase()
)
const nowCategory = computed<TurbulenceCategory>(
  () => update.value?.upcoming?.nowCategory ?? 'NoData'
)
const secondsAgo = computed(() => Math.max(0, Math.round((tick.value - lastUpdate.value) / 1000)))
// Updates come every 10 s; the stream reconnecting between them doesn't make the data stale.
const live = computed(() => lastUpdate.value > 0 && secondsAgo.value < 30)
const aheadMinutes = computed(() =>
  Math.max(update.value?.ahead?.segments.at(-1)?.endMinute ?? 1, 1)
)
const endLabel = computed(() => {
  const d = update.value?.ahead?.durationMinutes
  if (d === undefined) return ''
  if (!update.value?.usingRoute) return `+${formatDuration(d)}`
  const arrive = new Date(Date.now() + d * 60000).toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit'
  })
  return `Arrive ~${arrive}`
})

/** This flight, to download for offline use, once its route is known. */
const toDownload = computed(() =>
  update.value?.usingRoute && update.value.status === 'tracking'
    ? flightFromLive(update.value, Date.now())
    : undefined
)
/** The same flight, already downloaded on this phone. */
const saved = computed(() => {
  const f = toDownload.value
  return f && downloads.flights.value.find((d) => sameFlight(d, f))
})
const savedLink = computed(() =>
  saved.value ? { path: '/analyze', query: { flight: saved.value.id } } : undefined
)
/** What the download button shows: none until the flight can be downloaded, then where its download stands. */
const offline = computed(() => {
  if (!toDownload.value && !saved.value) return undefined
  if (!saved.value) return { text: 'Download for offline', short: 'Download', icon: 'mdi-download' }
  const state = downloads.states[saved.value.id]
  const s = packStatus(saved.value, state)
  if (s.ready) return { text: 'Ready offline', short: 'Offline', icon: 'mdi-check-circle' }
  const p = state?.syncing ? state.progress : undefined
  return { text: s.text, short: p ? `${p.done}/${p.total}` : 'Saved', icon: s.icon }
})
const downloading = ref(false)
const downloadError = ref<string>()

/** Downloads this flight for offline use; the map stays open and the button follows the download. */
async function download() {
  const f = toDownload.value
  if (!f) return
  downloading.value = true
  downloadError.value = undefined
  try {
    await downloads.download(f)
  } catch (e) {
    downloadError.value = `Couldn't download the flight on this phone: ${e}`
  } finally {
    downloading.value = false
  }
}

/** Share of the great-circle distance flown so far, for the origin → destination progress line. */
const progress = computed(() => {
  const r = update.value?.route
  const p = a.value
  if (!r || !p) return 0
  const flown = distanceKm(r.origin.lat, r.origin.lon, p.lat, p.lon)
  const left = distanceKm(p.lat, p.lon, r.destination.lat, r.destination.lon)
  return flown + left > 0 ? flown / (flown + left) : 0
})

function distanceKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180
  const dLat = (lat2 - lat1) * rad
  const dLon = (lon2 - lon1) * rad
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2
  return 12742 * Math.asin(Math.sqrt(h))
}

watch(
  () => props.hex,
  (hex) => {
    stop?.()
    update.value = undefined
    lastUpdate.value = 0
    stop = followFlight(hex, (u) => {
      update.value = u
      lastUpdate.value = Date.now()
      emit('update', u)
    })
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  stop?.()
  window.clearInterval(ticker)
})
</script>

<style scoped>
/* Desktop: a short strip along the bottom of the map, read left to right: the flight, now, the next 90 minutes. */
.follow-strip {
  position: absolute;
  z-index: 1001;
  left: 50%;
  bottom: 24px;
  transform: translateX(-50%);
  width: min(880px, calc(100% - 220px));
  display: grid;
  grid-template-columns: minmax(150px, 1fr) minmax(130px, 0.9fr) minmax(220px, 1.8fr) auto;
  gap: 20px;
  align-items: start;
  padding: 12px 14px 12px 16px;
  font-size: 13px;
}
.who-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.callsign {
  font-weight: 700;
  font-size: 18px;
  letter-spacing: 0.02em;
}
.type {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.14);
}
.live {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #86efac;
  margin-top: 2px;
}
.live::before {
  content: '';
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.25);
}
.live.off {
  color: rgba(255, 255, 255, 0.6);
}
.live.off::before {
  background: #94a3b8;
  box-shadow: none;
}
.progress {
  position: relative;
  height: 3px;
  margin: 10px 0 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.2);
}
.progress b {
  position: absolute;
  inset: 0 auto 0 0;
  border-radius: 2px;
  background: #7cc4f2;
}
.progress-plane {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%) rotate(90deg);
  color: #fff;
}
.ends {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 6px;
}
.code {
  font-weight: 700;
  font-size: 15px;
}
.muted {
  color: rgba(255, 255, 255, 0.7);
}
.label {
  font-size: 10px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.6);
}
.badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0;
  padding: 2px 12px 2px 9px;
  border-radius: 999px;
  font-weight: 600;
  font-size: 15px;
  background: color-mix(in srgb, var(--c) 30%, transparent);
  border: 1px solid color-mix(in srgb, var(--c) 70%, transparent);
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c);
}
.stats {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.headline {
  margin-bottom: 8px;
  line-height: 1.35;
}
.bar {
  position: relative;
  height: 12px;
  border-radius: 6px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.15);
}
.seg {
  position: absolute;
  top: 0;
  bottom: 0;
}
.ticks {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 4px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.65);
}
.lost {
  grid-column: span 2;
  color: rgba(255, 255, 255, 0.85);
  align-self: center;
}
.actions {
  grid-column: -2;
  grid-row: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}
.download-error {
  grid-column: 1 / -1;
  font-size: 12px;
  color: #fecaca;
}

/* Phones: a bottom sheet above the tab bar; tapping the handle folds it down to the flight line. */
.follow-strip.sheet {
  left: 8px;
  right: 8px;
  bottom: 8px;
  transform: none;
  width: auto;
  grid-template-columns: minmax(0, 1fr);
  gap: 10px;
  padding: 6px 14px 14px;
  background: rgba(24, 39, 52, 0.92);
}
.grab {
  justify-self: center;
  width: 40px;
  height: 18px;
  margin-bottom: -6px;
  background: none;
  border: none;
  cursor: pointer;
}
.grab::before {
  content: '';
  display: block;
  width: 36px;
  height: 4px;
  margin: 0 auto;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.4);
}
.sheet .now {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 10px;
}
.sheet .now .label {
  display: none;
}
.sheet .now .stats {
  margin-left: auto;
}
.sheet .ticks .worst {
  display: none;
}
</style>
