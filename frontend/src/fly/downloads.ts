/**
 * The flights downloaded for offline use, kept current from whichever page Turbol is on, the way a map downloaded
 * for offline use is. Each downloaded flight's Flight Pack is checked in the background (src/fly/sync.ts): one flight
 * at a time, the one in the air first; every few minutes in the air and less often on the ground; sooner after a
 * failed try or while Turbol is still loading after a restart; and as soon as the connection comes back or Turbol
 * comes to the front. While a downloaded flight is in the air, app updates wait until after it lands (see flying.ts).
 */
import { reactive, ref, watch } from 'vue'
import { type FlyStore, idbStore } from './db'
import { type SavedFlight, saveFlight } from './flights'
import { setFlying } from './flying'
import { type Estimate, estimate } from './position'
import { type SyncProgress, ping, removeFlight, syncFlight } from './sync'

const minute = 60_000

/** Where one flight's download stands. */
export interface DownloadState {
  syncing: boolean
  progress?: SyncProgress
  /** Why the last try failed, in the passenger's terms. */
  error?: string
  /** Set while Turbol has just restarted and is still loading turbulence data: how soon to ask again (ms). */
  loadingRetryMs?: number
  /** Tries in a row that failed. */
  failures: number
  /** When the last try ended (epoch ms). */
  lastAttempt?: number
  /** Check now, whatever the timer says: the flight was just downloaded, or the passenger asked. */
  force?: boolean
}

/**
 * How long after the last try to check a flight's pack again: every 3 minutes in the air and 10 on the ground, sooner
 * after a failed try (30 s, doubling each time) or while Turbol is still loading; never once the flight has landed.
 */
export function refreshDelay(
  phase: Estimate['phase'],
  s: Pick<DownloadState, 'failures' | 'loadingRetryMs'>
): number | undefined {
  if (phase === 'landed') return undefined
  const every = (phase === 'air' ? 3 : 10) * minute
  if (s.failures > 0) return Math.min(every, 0.5 * 2 ** (s.failures - 1) * minute)
  return Math.min(every, s.loadingRetryMs ?? Infinity)
}

/** Whether a flight's pack is due a check at `now`. */
export function isDue(f: SavedFlight, s: DownloadState, now: number): boolean {
  if (s.syncing) return false
  if (s.force) return true
  const delay = refreshDelay(estimate(f, now).phase, s)
  return delay !== undefined && (s.lastAttempt === undefined || now - s.lastAttempt >= delay)
}

/**
 * Downloaded flights in the order a passenger wants them: the one in the air, then the next to leave, then the ones
 * that have landed, latest first.
 */
export function inOrder(flights: SavedFlight[], now: number): SavedFlight[] {
  const score = (f: SavedFlight) => {
    const e = estimate(f, now)
    return e.phase === 'air' ? -1 : e.phase === 'gate' ? e.takeoff - now : 1e15 + now - e.takeoff
  }
  return flights
    .map((f) => [score(f), f] as const)
    .sort((a, b) => a[0] - b[0])
    .map(([, f]) => f)
}

/** Until when app updates wait: an hour after the latest landing of a downloaded flight in the air, to the minute. */
export function flyingUntil(flights: SavedFlight[], now: number): number | undefined {
  let until: number | undefined
  for (const f of flights) {
    const e = estimate(f, now)
    if (e.phase === 'air') until = Math.max(until ?? 0, e.landing + 3600_000)
  }
  return until === undefined ? undefined : Math.ceil(until / minute) * minute
}

export interface PackStatus {
  text: string
  color: string
  icon: string
  /** The whole pack is on the phone: the flight works with no connection. */
  ready: boolean
}

/** Where a flight's download stands, for a chip. */
export function packStatus(f: SavedFlight, s?: DownloadState): PackStatus {
  const p = s?.syncing ? s.progress : undefined
  const frames = f.manifest?.frames.length ?? 0
  if (p && p.done < p.total)
    return {
      text: `Downloading ${p.done} of ${p.total}`,
      color: 'blue-darken-2',
      icon: 'mdi-download',
      ready: false
    }
  if (s?.syncing && !f.manifest)
    return { text: 'Downloading', color: 'blue-darken-2', icon: 'mdi-download', ready: false }
  if (s?.loadingRetryMs !== undefined && frames === 0)
    return {
      text: 'Downloading soon',
      color: 'amber-darken-4',
      icon: 'mdi-timer-sand',
      ready: false
    }
  if (f.manifest && frames === 0)
    return {
      text: 'Forecast not out yet',
      color: 'blue-grey',
      icon: 'mdi-calendar-clock',
      ready: false
    }
  if (f.manifest)
    return {
      text: f.pending ? 'Ready offline, update paused' : 'Ready offline',
      color: 'green-darken-2',
      icon: 'mdi-check-circle',
      ready: true
    }
  return {
    text: 'Not downloaded yet',
    color: 'red-darken-2',
    icon: 'mdi-alert-circle',
    ready: false
  }
}

/** Bytes a flight's pack takes on the phone: one per cell, as stored. */
export const packBytes = (f: SavedFlight) =>
  (f.manifest?.frames ?? []).reduce(
    (sum, fr) => sum + f.manifest!.levelsFt.length * (fr.rowEnd - fr.rowStart) * f.manifest!.cols,
    0
  )

export interface DownloadsOptions {
  store?: FlyStore
  fetch?: typeof fetch
  clock?: () => number
}

export function createDownloads(options: DownloadsOptions = {}) {
  const store = options.store ?? idbStore
  const clock = options.clock ?? (() => Date.now())
  /** The downloaded flights, as stored on the phone. */
  const flights = ref<SavedFlight[]>([])
  /** Whether the flights have been read from the phone yet. */
  const loaded = ref(false)
  const states = reactive<Record<string, DownloadState>>({})
  /** The clock pages show the flights by: it ticks every 15 s. */
  const now = ref(clock())
  /** Whether Turbol itself answered the last check (a captive portal doesn't count); undefined before any. */
  const online = ref<boolean>()
  /** Set when this browser can't keep flights (private mode, storage turned off). */
  const storageError = ref<string>()

  let reading: Promise<void> | undefined
  let running: Promise<void> | undefined
  let again = false
  let started = false

  /** A flight's download state, made on first use (reactive: read back through `states`). */
  function stateOf(id: string): DownloadState {
    if (!states[id]) states[id] = { syncing: false, failures: 0 }
    return states[id]
  }

  /** Reads the downloaded flights from the phone, once. */
  function load(): Promise<void> {
    reading ??= store
      .flights()
      .then(
        (stored) => void (flights.value = stored),
        (e) => void (storageError.value = `this browser can't keep flights offline (${e}).`)
      )
      .finally(() => (loaded.value = true))
    return reading
  }

  /** Changes some fields of a flight on screen; a flight removed meanwhile stays removed. */
  function patch(id: string, fields: Partial<SavedFlight>) {
    const k = flights.value.findIndex((f) => f.id === id)
    if (k >= 0) flights.value.splice(k, 1, { ...flights.value[k], ...fields })
  }

  /** Checks the pack of every flight that is due, one at a time; resolves once none is left to check. */
  function refresh(): Promise<void> {
    if (running) {
      again = true
      return running
    }
    running = (async () => {
      try {
        do {
          again = false
          await round()
        } while (again)
      } finally {
        running = undefined
      }
    })()
    return running
  }

  async function round() {
    await load()
    const t = clock()
    now.value = t
    const due = inOrder(flights.value, t).filter((f) => isDue(f, stateOf(f.id), t))
    if (due.length === 0) return
    online.value = await ping(options.fetch)
    if (!online.value) {
      for (const f of due) missed(f.id)
      return
    }
    for (const [k, f] of due.entries()) {
      // Removed, or its takeoff changed, while the ones before it downloaded.
      const current = flights.value.find((x) => x.id === f.id)
      if (!current) continue
      if (!(await syncOne(current))) {
        // The connection dropped: the rest wait for the next window too.
        for (const rest of due.slice(k + 1)) missed(rest.id)
        return
      }
    }
  }

  /** A try that found no usable connection. */
  function missed(id: string) {
    const s = states[id]
    if (!s) return
    s.failures += 1
    s.loadingRetryMs = undefined
    s.force = false
    s.lastAttempt = clock()
  }

  /** Brings one flight's pack up to date; false when the connection dropped on the way. */
  async function syncOne(f: SavedFlight): Promise<boolean> {
    const s = stateOf(f.id)
    s.syncing = true
    s.force = false
    try {
      const result = await syncFlight(store, f, {
        fetch: options.fetch,
        now: clock,
        onProgress: (p) => (s.progress = p)
      })
      const { manifest, pending, checkedAt } = result.flight
      patch(f.id, { manifest, pending, checkedAt })
      s.loadingRetryMs = result.status === 'loading' ? result.retryAfterMs : undefined
      if (result.status === 'offline') {
        s.failures += 1
        s.error = 'the connection dropped; Turbol picks up where it left off next time.'
        return false
      }
      s.failures = 0
      s.error = undefined
      // Ask the browser not to clear the packs to make room (iOS does, otherwise, after a week unused).
      if (result.status === 'updated') globalThis.navigator?.storage?.persist?.().catch(() => false)
      return true
    } catch (e) {
      s.failures += 1
      s.loadingRetryMs = undefined
      s.error = String(e)
      return true
    } finally {
      s.syncing = false
      s.progress = undefined
      s.lastAttempt = clock()
    }
  }

  /** Saves a flight on the phone and starts downloading its pack. Downloading one already there keeps its pack. */
  async function download(f: SavedFlight): Promise<SavedFlight> {
    await load()
    const saved = await saveFlight(store, f)
    const k = flights.value.findIndex((x) => x.id === saved.id)
    if (k >= 0) flights.value.splice(k, 1, saved)
    else flights.value.push(saved)
    const s = stateOf(saved.id)
    s.failures = 0
    s.force = true
    void refresh()
    return saved
  }

  /** Checks a flight's pack right away: the passenger tapped Update now. */
  function syncNow(id: string): Promise<void> {
    const s = stateOf(id)
    s.failures = 0
    s.force = true
    return refresh()
  }

  /**
   * Checks soon the flights (all, or those listed) not checked in the last minute: Turbol came to the front, or a
   * flight was opened.
   */
  async function wake(ids?: string[]): Promise<void> {
    await load()
    const t = clock()
    now.value = t
    for (const f of flights.value) {
      if (ids && !ids.includes(f.id)) continue
      const s = stateOf(f.id)
      const stale = s.lastAttempt === undefined || t - s.lastAttempt > minute
      if (stale && estimate(f, t).phase !== 'landed') s.force = true
    }
    return refresh()
  }

  /** Forgets a downloaded flight and the slabs only it needed. */
  async function remove(id: string): Promise<void> {
    await removeFlight(store, id)
    flights.value = flights.value.filter((f) => f.id !== id)
    delete states[id]
  }

  /** Keeps what the phone knows about a flight's takeoff and position, leaving the pack to the download. */
  async function savePosition(id: string, fields: Pick<SavedFlight, 'takeoff' | 'airborneFix'>) {
    patch(id, fields)
    await store.updateFlight(id, (f) => ({ ...f, ...fields }))
  }

  /** Keeps the downloaded flights current while Turbol is open, whatever page it's on. Call once. */
  function start() {
    if (started) return
    started = true
    window.setInterval(() => {
      now.value = clock()
      void refresh()
    }, 15_000)
    window.addEventListener('online', () => {
      for (const f of flights.value) {
        const s = stateOf(f.id)
        s.failures = 0
        s.force = estimate(f, clock()).phase !== 'landed'
      }
      void refresh()
    })
    window.addEventListener('offline', () => (online.value = false))
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') void wake()
    })
    // Only once the flights are read: clearing the marker before then could let a new build in over plane Wi-Fi.
    void load().then(() => {
      if (storageError.value) return
      watch(
        () => flyingUntil(flights.value, now.value),
        (until) => void setFlying(until),
        { immediate: true }
      )
    })
    void refresh()
  }

  return {
    flights,
    loaded,
    states,
    now,
    online,
    storageError,
    load,
    refresh,
    download,
    syncNow,
    wake,
    remove,
    savePosition,
    start
  }
}

export type Downloads = ReturnType<typeof createDownloads>

/** The phone's downloaded flights. */
export const downloads = createDownloads()
