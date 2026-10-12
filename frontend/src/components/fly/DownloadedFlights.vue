<template>
  <v-card class="panel downloads px-4 py-3">
    <div class="eyebrow">Downloaded for offline</div>
    <div v-for="f in ordered" :key="f.id" class="row">
      <RouterLink class="flight" :to="{ path: '/analyze', query: { flight: f.id } }">
        <v-icon :icon="phaseIcon(f)" size="20" class="plane" />
        <span class="text">
          <span class="name"
            >{{ f.label
            }}<span v-if="f.label !== routeOf(f)" class="muted"> · {{ routeOf(f) }}</span></span
          >
          <span class="muted small">{{ detail(f) }}</span>
        </span>
      </RouterLink>
      <template v-if="confirming !== f.id">
        <v-chip
          size="small"
          variant="flat"
          :color="packStatus(f, states[f.id]).color"
          :prepend-icon="packStatus(f, states[f.id]).icon"
        >
          {{ packStatus(f, states[f.id]).text }}
        </v-chip>
        <v-btn
          icon="mdi-delete-outline"
          size="small"
          variant="text"
          density="comfortable"
          :aria-label="`Remove ${f.label}`"
          @click="confirming = f.id"
        />
      </template>
      <span v-else class="confirm">
        <v-btn color="red-darken-2" variant="flat" size="small" @click="remove(f.id)">Remove</v-btn>
        <v-btn variant="text" size="small" @click="confirming = undefined">Keep</v-btn>
      </span>
    </div>
  </v-card>
</template>

<script setup lang="ts">
/**
 * The flights on this phone, like a list of maps downloaded for offline use: where each download stands, how big it
 * is, when it was last brought up to date, and a way to remove it.
 */
import { computed, ref } from 'vue'
import { formatDuration } from '@/api/flights'
import { downloads, inOrder, packBytes, packStatus } from '@/fly/downloads'
import type { SavedFlight } from '@/fly/flights'
import { estimate } from '@/fly/position'

const { now, states } = downloads
const ordered = computed(() => inOrder(downloads.flights.value, now.value))
const confirming = ref<string>()

const routeOf = (f: SavedFlight) => `${f.origin.code} → ${f.destination.code}`
const time = (ms: number) =>
  new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
const day = (ms: number) =>
  new Date(ms).toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' })

function phaseIcon(f: SavedFlight) {
  const phase = estimate(f, now.value).phase
  return phase === 'air'
    ? 'mdi-airplane'
    : phase === 'gate'
      ? 'mdi-airplane-takeoff'
      : 'mdi-airplane-landing'
}

/** When it flies, how much it takes on the phone, and when it was last brought up to date. */
function detail(f: SavedFlight): string {
  const e = estimate(f, now.value)
  const when =
    e.phase === 'gate'
      ? `Departs ${day(f.departure)}, ${time(f.departure)}`
      : e.phase === 'air'
        ? `In the air, lands about ${time(e.landing)}`
        : `Landed ${day(e.landing)}, ${time(e.landing)}`
  const bytes = packBytes(f)
  const size = bytes > 0 ? ` · ${(bytes / 1e6).toFixed(1)} MB` : ''
  const age = f.checkedAt === undefined ? undefined : now.value - f.checkedAt
  const checked =
    age === undefined
      ? ''
      : ` · updated ${age < 60000 ? 'just now' : `${formatDuration(age / 60000)} ago`}`
  return when + size + checked
}

async function remove(id: string) {
  confirming.value = undefined
  await downloads.remove(id)
}
</script>

<style scoped>
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
  margin-bottom: 2px;
}
.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  padding: 6px 0;
}
.row + .row {
  border-top: 1px solid rgba(15, 23, 42, 0.1);
}
.flight {
  flex: 1 1 240px;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  color: inherit;
  text-decoration: none;
}
.plane {
  color: #10324f;
}
.text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.name {
  font-size: 16px;
  font-weight: 600;
}
.confirm {
  display: inline-flex;
  gap: 4px;
}
.muted {
  color: #475569;
  font-weight: 400;
}
.small {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
</style>
