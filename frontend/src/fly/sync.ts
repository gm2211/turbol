/**
 * Keeps a saved flight's Flight Pack current whenever the phone has a connection, however briefly: fetch the newest
 * manifest, download the slabs the phone doesn't have yet (most useful first, a few at a time, each with a timeout),
 * and switch to the new pack only once all of its slabs are stored. An interrupted download resumes in the next
 * window, and slabs no saved flight needs any more are deleted.
 */
import type { FlyStore } from './db'
import { type SavedFlight, manifestUrl, referencedSlabs } from './flights'
import type { PackFrameInfo, PackManifest } from './pack'

/** The pack format this phone reads (backend FlightPack.version). */
export const packVersion = 2

export interface SyncProgress {
  done: number
  total: number
}

export interface SyncOptions {
  fetch?: typeof fetch
  now?: () => number
  manifestTimeoutMs?: number
  slabTimeoutMs?: number
  /** Slabs downloaded at once: plane Wi-Fi is slow per request more than per byte. */
  parallel?: number
  onProgress?: (p: SyncProgress) => void
}

export type SyncResult =
  /** The newest pack is in use: just downloaded, or already there. */
  | { status: 'updated' | 'current'; flight: SavedFlight }
  /** No usable connection (offline, a captive portal, a dead link); `flight` is unchanged or partly downloaded. */
  | { status: 'offline'; flight: SavedFlight; error: string }
  /**
   * Turbol has just restarted and is still loading turbulence data, so it has no whole pack to give yet. `flight` is
   * unchanged, keeping any pack it has; ask again after `retryAfterMs`.
   */
  | { status: 'loading'; flight: SavedFlight; retryAfterMs: number }

class Unreachable extends Error {}

class ServerLoading extends Error {
  retryAfterMs: number
  constructor(retryAfterMs: number) {
    super('Turbol is still loading turbulence data')
    this.retryAfterMs = retryAfterMs
  }
}

/** Fetch with a deadline: a crawling plane link counts as no link. Statuses in `allow` come back like a 200. */
async function fetchWithin(
  f: typeof fetch,
  url: string,
  timeoutMs: number,
  init: RequestInit = {},
  allow: number[] = []
): Promise<Response> {
  const abort = new AbortController()
  const timer = setTimeout(() => abort.abort(), timeoutMs)
  try {
    const res = await f(url, { ...init, signal: abort.signal })
    if (!res.ok && !allow.includes(res.status)) throw new Unreachable(`${url}: HTTP ${res.status}`)
    return res
  } catch (e) {
    throw e instanceof Unreachable ? e : new Unreachable(`${url}: ${e}`)
  } finally {
    clearTimeout(timer)
  }
}

/** Whether a response really is Turbol's (a captive portal answers everything with its own page). */
function expectType(res: Response, type: string) {
  const got = res.headers.get('Content-Type') ?? ''
  if (!got.startsWith(type)) throw new Unreachable(`${res.url}: got ${got || 'no content type'}`)
}

/** /api/ping: true only when Turbol itself answered, quickly. */
export async function ping(f: typeof fetch = fetch, timeoutMs = 5000): Promise<boolean> {
  try {
    const res = await fetchWithin(f, '/api/ping', timeoutMs, { cache: 'no-store' })
    expectType(res, 'application/json')
    return (await res.json())?.ok === 'turbol'
  } catch {
    return false
  }
}

export async function fetchManifest(
  flight: SavedFlight,
  f: typeof fetch = fetch,
  timeoutMs = 20000
): Promise<PackManifest> {
  const res = await fetchWithin(f, manifestUrl(flight), timeoutMs, { cache: 'no-store' }, [503])
  expectType(res, 'application/json')
  if (res.status === 503) {
    // Turbol's own 503 (JSON, which a captive portal or the host's error page isn't): just restarted, still loading.
    const body = await res.json().catch(() => undefined)
    if (body?.loading !== true) throw new Unreachable(`${res.url}: HTTP 503`)
    const after = Number(body.retryAfterSeconds)
    throw new ServerLoading(
      Number.isFinite(after) ? Math.min(300, Math.max(10, after)) * 1000 : 30_000
    )
  }
  const m = (await res.json()) as PackManifest
  if (m?.version !== packVersion || !Array.isArray(m.frames))
    throw new Error(`Unexpected pack version ${m?.version}: reload Turbol to update it`)
  return m
}

const sameFrames = (a?: PackManifest, b?: PackManifest) =>
  !!a &&
  !!b &&
  a.frames.length === b.frames.length &&
  a.frames.every((f, k) => f.url === b.frames[k].url)

/**
 * The order to download a pack's slabs in: the nowcast, then the GTG hours, then the global layer, each soonest
 * first, with hours that are already over last.
 */
export function downloadOrder(frames: PackFrameInfo[], now: number): PackFrameInfo[] {
  const rank = { nowcast: 0, forecast: 1, global: 2 }
  const over = (f: PackFrameInfo) => Date.parse(f.validTime) < now - 3 * 3600_000
  return [...frames].sort(
    (a, b) =>
      Number(over(a)) - Number(over(b)) ||
      rank[a.kind] - rank[b.kind] ||
      Date.parse(a.validTime) - Date.parse(b.validTime)
  )
}

/** Bytes a slab must have: levels x rows x columns, one byte each. */
const slabSize = (m: PackManifest, f: PackFrameInfo) =>
  m.levelsFt.length * (f.rowEnd - f.rowStart) * m.cols

export async function syncFlight(
  store: FlyStore,
  flight: SavedFlight,
  options: SyncOptions = {}
): Promise<SyncResult> {
  const f = options.fetch ?? fetch
  const now = options.now ?? Date.now
  let manifest: PackManifest
  try {
    manifest = await fetchManifest(flight, f, options.manifestTimeoutMs)
  } catch (e) {
    if (e instanceof Unreachable) return { status: 'offline', flight, error: e.message }
    if (e instanceof ServerLoading)
      return { status: 'loading', flight, retryAfterMs: e.retryAfterMs }
    throw e
  }
  if (sameFrames(manifest, flight.manifest)) {
    const current = await savePack(store, flight, {
      manifest,
      pending: undefined,
      checkedAt: now()
    })
    await cleanUp(store)
    return { status: 'current', flight: current }
  }

  const working = await savePack(store, flight, { pending: manifest })
  const missing: PackFrameInfo[] = []
  for (const fr of downloadOrder(manifest.frames, now()))
    if (!(await store.hasSlab(fr.url))) missing.push(fr)
  const total = manifest.frames.length
  let done = total - missing.length
  options.onProgress?.({ done, total })

  let failure: Error | undefined
  const queue = [...missing]
  const worker = async () => {
    while (queue.length > 0 && !failure) {
      const fr = queue.shift()!
      try {
        const res = await fetchWithin(f, fr.url, options.slabTimeoutMs ?? 60000)
        expectType(res, 'application/octet-stream')
        const bytes = new Uint8Array(await res.arrayBuffer())
        if (bytes.length !== slabSize(manifest, fr))
          throw new Unreachable(
            `${fr.url}: ${bytes.length} bytes, expected ${slabSize(manifest, fr)}`
          )
        await store.putSlab(fr.url, bytes)
        done += 1
        options.onProgress?.({ done, total })
      } catch (e) {
        failure ??= e instanceof Error ? e : new Error(String(e))
      }
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, options.parallel ?? 3) }, worker))
  if (failure) {
    if (failure instanceof Unreachable)
      return { status: 'offline', flight: working, error: failure.message }
    throw failure
  }

  const updated = await savePack(store, flight, { manifest, pending: undefined, checkedAt: now() })
  await cleanUp(store)
  return { status: 'updated', flight: updated }
}

/** The fields of a saved flight that describe its pack: the download writes these and nothing else. */
export type PackFields = Partial<Pick<SavedFlight, 'manifest' | 'pending' | 'checkedAt'>>

/**
 * Writes pack fields over the stored flight, keeping what changed meanwhile (a takeoff tap or GPS fix during a slow
 * download). A flight removed meanwhile stays removed.
 */
async function savePack(
  store: FlyStore,
  flight: SavedFlight,
  fields: PackFields
): Promise<SavedFlight> {
  const stored = await store.updateFlight(flight.id, (f) => ({ ...f, ...fields }))
  return stored ?? { ...flight, ...fields }
}

/** Deletes the slabs no saved flight lists any more. */
export async function cleanUp(store: FlyStore): Promise<void> {
  const keep = referencedSlabs(await store.flights())
  for (const url of await store.slabUrls()) if (!keep.has(url)) await store.deleteSlab(url)
}

/** Forgets a flight and the slabs only it needed. */
export async function removeFlight(store: FlyStore, id: string): Promise<void> {
  await store.deleteFlight(id)
  await cleanUp(store)
}
