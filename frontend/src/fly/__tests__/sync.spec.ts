import { describe, expect, it } from 'vitest'
import { memoryStore } from '../db'
import { type SavedFlight, loadPack, newFlight, saveFlight } from '../flights'
import { PackGrid } from '../geo'
import type { PackFrameInfo, PackManifest } from '../pack'
import { downloadOrder, packVersion, syncFlight } from '../sync'

const jfk = { code: 'JFK', name: 'New York JFK', lat: 40.6413, lon: -73.7781 }
const bos = { code: 'BOS', name: 'Boston Logan', lat: 42.3656, lon: -71.0096 }
const departure = Date.parse('2026-01-15T14:00:00Z')
const flight = (): SavedFlight =>
  newFlight({
    label: 'B6 1',
    origin: jfk,
    destination: bos,
    departure,
    aircraftClass: 'Medium'
  })

const levelsFt = [10000, 20000, 30000, 35000]
const rows = new PackGrid(jfk.lat, jfk.lon, bos.lat, bos.lon).rows
const cols = 81

function frame(kind: PackFrameInfo['kind'], hour: number): PackFrameInfo {
  const valid = new Date(departure + hour * 3600_000).toISOString()
  const id = `${kind[0]}${hour}`
  return {
    id,
    kind,
    validTime: valid,
    issued: '2026-01-15T12:00:00Z',
    rowStart: 0,
    rowEnd: rows,
    url: `/api/packs/slab/${id}?rows=0-${rows}`
  }
}

function manifest(frames: PackFrameInfo[]): PackManifest {
  return {
    version: packVersion,
    origin: { lat: jfk.lat, lon: jfk.lon },
    destination: { lat: bos.lat, lon: bos.lon },
    departure: new Date(departure).toISOString(),
    totalKm: 300,
    rows,
    cols,
    alongStepKm: 6,
    crossStepKm: 6,
    levelsFt,
    frames,
    thresholds: {
      Light: { light: 0.1, moderate: 0.11, severe: 0.36, extreme: 0.64 },
      Medium: { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 },
      Heavy: { light: 0.14, moderate: 0.17, severe: 0.54, extreme: 0.96 }
    },
    generatedAt: '2026-01-15T13:00:00Z'
  }
}

/** A Turbol server that serves `current` and records what the phone asked for. */
function server(state: { current: PackManifest; failing?: Set<string>; portal?: boolean }) {
  const asked: string[] = []
  const fetchFn = async (input: RequestInfo | URL): Promise<Response> => {
    const url = String(input)
    asked.push(url)
    if (state.portal)
      return new Response('<html>Sign in to Wi-Fi</html>', {
        headers: { 'Content-Type': 'text/html' }
      })
    if (url.startsWith('/api/packs/manifest'))
      return new Response(JSON.stringify(state.current), {
        headers: { 'Content-Type': 'application/json' }
      })
    if (state.failing?.has(url)) throw new TypeError('Failed to fetch')
    const size = levelsFt.length * rows * cols
    return new Response(new Uint8Array(size).fill(20), {
      headers: { 'Content-Type': 'application/octet-stream' }
    })
  }
  return { fetch: fetchFn as typeof fetch, asked }
}

describe('keeping a flight pack current', () => {
  it('downloads every slab once, then only what a newer pack adds, switching when it is complete', async () => {
    const store = memoryStore()
    const first = manifest([frame('nowcast', 0), frame('forecast', 1), frame('forecast', 2)])
    const state = { current: first, failing: new Set<string>() }
    const s = server(state)
    const progress: number[] = []
    await store.putFlight(flight())
    let r = await syncFlight(store, flight(), {
      fetch: s.fetch,
      onProgress: (p) => progress.push(p.done)
    })
    expect(r.status).toBe('updated')
    expect(r.flight.manifest?.frames.map((f) => f.id)).toEqual(['n0', 'f1', 'f2'])
    expect(progress).toEqual([0, 1, 2, 3])
    expect((await store.slabUrls()).length).toBe(3)
    expect(await loadPack(store, r.flight.manifest!)).toBeDefined()

    // Nothing new: one small request, no slabs.
    s.asked.length = 0
    r = await syncFlight(store, r.flight, { fetch: s.fetch })
    expect(r.status).toBe('current')
    expect(s.asked.filter((u) => u.includes('/slab/'))).toEqual([])

    // A new nowcast and GTG cycle; the window closes before the last slab arrives.
    const second = manifest([frame('nowcast', 1), frame('forecast', 2), frame('forecast', 3)])
    state.current = second
    state.failing.add(second.frames[2].url)
    s.asked.length = 0
    r = await syncFlight(store, r.flight, { fetch: s.fetch, parallel: 1 })
    expect(r.status).toBe('offline')
    expect(s.asked.filter((u) => u.includes('/slab/'))).toEqual([
      second.frames[0].url,
      second.frames[2].url
    ])
    // Still flying on the complete older pack, with the newer one half there.
    expect(r.flight.manifest?.frames.map((f) => f.id)).toEqual(['n0', 'f1', 'f2'])
    expect(r.flight.pending?.frames.map((f) => f.id)).toEqual(['n1', 'f2', 'f3'])

    // The next window finishes it, and the slabs only the old pack used are deleted.
    state.failing.clear()
    s.asked.length = 0
    r = await syncFlight(store, r.flight, { fetch: s.fetch })
    expect(r.status).toBe('updated')
    expect(s.asked.filter((u) => u.includes('/slab/'))).toEqual([second.frames[2].url])
    expect(r.flight.pending).toBeUndefined()
    expect((await store.slabUrls()).sort()).toEqual(second.frames.map((f) => f.url).sort())
  })

  it('treats a captive portal or a wrong-sized slab as no connection, and keeps nothing from it', async () => {
    const store = memoryStore()
    await store.putFlight(flight())
    const m = manifest([frame('forecast', 1)])
    const portal = await syncFlight(store, flight(), {
      fetch: server({ current: m, portal: true }).fetch
    })
    expect(portal.status).toBe('offline')

    const short: typeof fetch = async (input) =>
      String(input).includes('/manifest')
        ? new Response(JSON.stringify(m), { headers: { 'Content-Type': 'application/json' } })
        : new Response(new Uint8Array(10), {
            headers: { 'Content-Type': 'application/octet-stream' }
          })
    const r = await syncFlight(store, flight(), { fetch: short })
    expect(r.status).toBe('offline')
    expect(await store.slabUrls()).toEqual([])
  })

  it('keeps a takeoff tap or GPS fix saved while a slow download runs', async () => {
    const store = memoryStore()
    const saved = flight()
    await store.putFlight(saved)
    const m = manifest([frame('nowcast', 0), frame('forecast', 1)])
    const s = server({ current: m })
    const fix = { time: departure + 40 * 60_000, lat: 41, lon: -72.6, speedMps: 200 }
    // Mid-download, the Fly view stores a GPS fix, the way it does: only the fields it owns.
    const slow: typeof fetch = async (input, init) => {
      if (String(input).includes('/slab/'))
        await store.updateFlight(saved.id, (f) => ({ ...f, airborneFix: fix }))
      return s.fetch(input, init)
    }
    const r = await syncFlight(store, saved, { fetch: slow })
    expect(r.status).toBe('updated')
    const [stored] = await store.flights()
    expect(stored.airborneFix).toEqual(fix)
    expect(stored.manifest?.frames.map((f) => f.id)).toEqual(['n0', 'f1'])
    expect(r.flight).toEqual(stored)
  })

  it('leaves a flight removed during its download removed, and its slabs gone', async () => {
    const store = memoryStore()
    const saved = flight()
    await store.putFlight(saved)
    const s = server({ current: manifest([frame('nowcast', 0), frame('forecast', 1)]) })
    const removing: typeof fetch = async (input, init) => {
      if (String(input).includes('/slab/')) await store.deleteFlight(saved.id)
      return s.fetch(input, init)
    }
    await syncFlight(store, saved, { fetch: removing })
    expect(await store.flights()).toEqual([])
    expect(await store.slabUrls()).toEqual([])
  })

  it('keeps the pack and takeoff of a flight picked again', async () => {
    const store = memoryStore()
    await store.putFlight(flight())
    const r = await syncFlight(store, flight(), {
      fetch: server({ current: manifest([frame('forecast', 1)]) }).fetch
    })
    const takeoff = { time: departure + 20 * 60_000, source: 'tap' as const }
    await store.updateFlight(r.flight.id, (f) => ({ ...f, takeoff }))
    const again = await saveFlight(store, { ...flight(), label: 'JetBlue 1' })
    expect(again.label).toBe('JetBlue 1')
    expect(again.manifest).toEqual(r.flight.manifest)
    expect(again.takeoff).toEqual(takeoff)
    expect(await store.flights()).toEqual([again])
  })

  it('asks for a reload when the server speaks a newer pack format', async () => {
    const m = { ...manifest([]), version: packVersion + 1 }
    await expect(
      syncFlight(memoryStore(), flight(), { fetch: server({ current: m }).fetch })
    ).rejects.toThrow(/reload Turbol/)
  })

  it('downloads the nowcast first, then GTG, then the global layer, past hours last', () => {
    const now = departure + 3600_000
    const order = downloadOrder(
      [
        frame('global', 0),
        frame('forecast', -5),
        frame('forecast', 2),
        frame('forecast', 1),
        frame('nowcast', 1)
      ],
      now
    )
    expect(order.map((f) => f.id)).toEqual(['n1', 'f1', 'f2', 'g0', 'f-5'])
  })
})
