import { describe, expect, it } from 'vitest'
import type { LiveFlightUpdate } from '@/api/follow'
import { memoryStore } from '../db'
import {
  createDownloads,
  flyingUntil,
  inOrder,
  packBytes,
  packStatus,
  refreshDelay
} from '../downloads'
import { type SavedFlight, newFlight } from '../flights'
import { PackGrid, greatCircleKm, intermediate } from '../geo'
import { flightFromLive, sameFlight } from '../live'
import type { PackManifest } from '../pack'
import { estimate } from '../position'
import { packVersion } from '../sync'

const jfk = { code: 'JFK', name: 'New York JFK', lat: 40.6413, lon: -73.7781 }
const bos = { code: 'BOS', name: 'Boston Logan', lat: 42.3656, lon: -71.0096 }
const lax = { code: 'LAX', name: 'Los Angeles Intl', lat: 33.9416, lon: -118.4085 }
const t0 = Date.parse('2026-01-15T14:00:00Z')
const minute = 60_000
const levelsFt = [10000, 20000, 30000, 35000]

const flight = (label: string, to: typeof bos, departure: number): SavedFlight =>
  newFlight({ label, origin: jfk, destination: to, departure, aircraftClass: 'Medium' })

const gridOf = (q: URLSearchParams) => {
  const [olat, olon] = q.get('o')!.split(',').map(Number)
  const [dlat, dlon] = q.get('d')!.split(',').map(Number)
  return new PackGrid(olat, olon, dlat, dlon)
}

/** The pack a Turbol server builds for any flight: one forecast hour, the whole corridor. */
function manifestFor(url: string): PackManifest {
  const q = new URL(url, 'https://turbol.test').searchParams
  const grid = gridOf(q)
  const dep = Date.parse(q.get('dep')!)
  return {
    version: packVersion,
    origin: { lat: grid.originLat, lon: grid.originLon },
    destination: { lat: grid.destLat, lon: grid.destLon },
    departure: new Date(dep).toISOString(),
    totalKm: grid.totalKm,
    rows: grid.rows,
    cols: grid.cols,
    alongStepKm: grid.alongStepKm,
    crossStepKm: grid.crossStepKm,
    levelsFt,
    frames: [
      {
        id: 'f1',
        kind: 'forecast',
        validTime: new Date(dep + 3600_000).toISOString(),
        issued: new Date(dep).toISOString(),
        rowStart: 0,
        rowEnd: grid.rows,
        url: `/api/packs/slab/f1?o=${q.get('o')}&d=${q.get('d')}&rows=0-${grid.rows}`
      }
    ],
    thresholds: {
      Light: { light: 0.1, moderate: 0.11, severe: 0.36, extreme: 0.64 },
      Medium: { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 },
      Heavy: { light: 0.14, moderate: 0.17, severe: 0.54, extreme: 0.96 }
    },
    generatedAt: new Date(dep).toISOString()
  }
}

/** A Turbol server, or none at all while `down`; records the packs the phone asked for. */
function server(state: { down?: boolean } = {}) {
  const manifests: string[] = []
  const fetchFn = async (input: RequestInfo | URL): Promise<Response> => {
    const url = String(input)
    if (state.down) throw new TypeError('Failed to fetch')
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
    if (url.startsWith('/api/ping')) return json({ ok: 'turbol' })
    if (url.startsWith('/api/packs/manifest')) {
      manifests.push(url)
      return json(manifestFor(url))
    }
    const q = new URL(url, 'https://turbol.test').searchParams
    const grid = gridOf(q)
    return new Response(new Uint8Array(levelsFt.length * grid.rows * grid.cols).fill(20), {
      headers: { 'Content-Type': 'application/octet-stream' }
    })
  }
  /** Where the packs asked for were going, in order. */
  const destinations = () =>
    manifests.map((u) => new URL(u, 'https://turbol.test').searchParams.get('d'))
  return { fetch: fetchFn as typeof fetch, manifests, destinations }
}

const toLax = `${lax.lat.toFixed(4)},${lax.lon.toFixed(4)}`
const toBos = `${bos.lat.toFixed(4)},${bos.lon.toFixed(4)}`

describe('flights downloaded for offline use', () => {
  it('keeps every one current from any page, the one in the air first and more often', async () => {
    const store = memoryStore()
    const later = flight('B6 1', bos, t0 + 3 * 60 * minute)
    const flying = flight('B6 23', lax, t0 - 2 * 60 * minute)
    await store.putFlight(later)
    await store.putFlight(flying)
    let now = t0
    const s = server()
    const d = createDownloads({ store, fetch: s.fetch, clock: () => now })

    await d.refresh()
    expect(s.destinations()).toEqual([toLax, toBos])
    for (const f of d.flights.value)
      expect(packStatus(f, d.states[f.id]).text).toBe('Ready offline')

    // Three minutes on, only the flight in the air is checked again: the one at the gate waits ten.
    now += 3 * minute
    s.manifests.length = 0
    await d.refresh()
    expect(s.destinations()).toEqual([toLax])
    now += 7 * minute
    s.manifests.length = 0
    await d.refresh()
    expect(s.destinations()).toEqual([toLax, toBos])
  })

  it('backs off while there is no connection, and checks again when asked', async () => {
    const store = memoryStore()
    let now = t0
    const state = { down: true }
    const s = server(state)
    const d = createDownloads({ store, fetch: s.fetch, clock: () => now })
    const saved = await d.download(flight('B6 1', bos, t0 + 2 * 60 * minute))
    await d.refresh()
    expect(d.online.value).toBe(false)
    expect(d.states[saved.id].failures).toBe(1)
    expect(packStatus(d.flights.value[0], d.states[saved.id]).text).toBe('Not downloaded yet')

    // The first retry comes 30 s later, the next a minute after that.
    now += 20_000
    await d.refresh()
    expect(d.states[saved.id].failures).toBe(1)
    now += 15_000
    await d.refresh()
    expect(d.states[saved.id].failures).toBe(2)

    // Back online, the passenger taps Update now.
    state.down = false
    await d.syncNow(saved.id)
    expect(d.online.value).toBe(true)
    expect(d.states[saved.id].failures).toBe(0)
    expect(packStatus(d.flights.value[0], d.states[saved.id])).toMatchObject({
      text: 'Ready offline',
      ready: true
    })
    // What the list says the download takes is what is stored.
    const [stored] = d.flights.value
    expect(packBytes(stored)).toBe((await store.getSlab(stored.manifest!.frames[0].url))!.length)
  })

  it('forgets a removed flight and the turbulence only it needed', async () => {
    const store = memoryStore()
    const s = server()
    const d = createDownloads({ store, fetch: s.fetch, clock: () => t0 })
    const one = await d.download(flight('B6 1', bos, t0 + 60 * minute))
    const two = await d.download(flight('B6 23', lax, t0 + 90 * minute))
    await d.refresh()
    expect(await store.slabUrls()).toHaveLength(2)

    await d.remove(one.id)
    expect(d.flights.value.map((f) => f.id)).toEqual([two.id])
    expect((await store.flights()).map((f) => f.id)).toEqual([two.id])
    expect(await store.slabUrls()).toEqual(d.flights.value[0].manifest!.frames.map((f) => f.url))
  })

  it('waits on the clock it has: every 3 minutes in the air, 10 on the ground, sooner after a failure', () => {
    expect(refreshDelay('air', { failures: 0 })).toBe(3 * minute)
    expect(refreshDelay('gate', { failures: 0 })).toBe(10 * minute)
    expect(refreshDelay('gate', { failures: 1 })).toBe(30_000)
    expect(refreshDelay('gate', { failures: 3 })).toBe(2 * minute)
    expect(refreshDelay('air', { failures: 9 })).toBe(3 * minute)
    expect(refreshDelay('gate', { failures: 0, loadingRetryMs: 30_000 })).toBe(30_000)
    expect(refreshDelay('landed', { failures: 0 })).toBeUndefined()
  })

  it('lists the flight in the air first, then the next to leave, and holds app updates until after landing', () => {
    const landed = flight('landed', bos, t0 - 5 * 60 * minute)
    const later = flight('later', lax, t0 + 8 * 60 * minute)
    const soon = flight('soon', bos, t0 + 60 * minute)
    const flying = flight('flying', lax, t0 - 60 * minute)
    expect(inOrder([landed, later, soon, flying], t0).map((f) => f.label)).toEqual([
      'flying',
      'soon',
      'later',
      'landed'
    ])
    const lands = estimate(flying, t0).landing
    expect(flyingUntil([soon, flying], t0)).toBe(Math.ceil((lands + 3600_000) / minute) * minute)
    expect(flyingUntil([soon, landed], t0)).toBeUndefined()
  })

  it('says where each download stands', () => {
    const f = flight('B6 1', bos, t0)
    const m = manifestFor(
      `/api/packs/manifest?o=${jfk.lat},${jfk.lon}&d=${toBos}&dep=${new Date(t0).toISOString()}`
    )
    const idle = { syncing: false, failures: 0 }
    expect(packStatus(f).text).toBe('Not downloaded yet')
    expect(packStatus(f, { ...idle, syncing: true }).text).toBe('Downloading')
    expect(
      packStatus(
        { ...f, manifest: m },
        { ...idle, syncing: true, progress: { done: 3, total: 15 } }
      ).text
    ).toBe('Downloading 3 of 15')
    expect(packStatus(f, { ...idle, loadingRetryMs: 30_000 }).text).toBe('Downloading soon')
    expect(packStatus({ ...f, manifest: { ...m, frames: [] } }, idle).text).toBe(
      'Forecast not out yet'
    )
    expect(packStatus({ ...f, manifest: m }, idle)).toMatchObject({
      text: 'Ready offline',
      ready: true
    })
    expect(packStatus({ ...f, manifest: m, pending: m }, idle).text).toBe(
      'Ready offline, update paused'
    )
  })

  it('downloads a flight followed on the live map from where the plane is', () => {
    const [lat, lon] = intermediate(jfk.lat, jfk.lon, lax.lat, lax.lon, 1 / 3)
    const seen: LiveFlightUpdate = {
      time: new Date(t0).toISOString(),
      status: 'tracking',
      aircraft: {
        hex: 'a1b2c3',
        callsign: 'UAL1',
        typeCode: 'B39M',
        lat,
        lon,
        altitudeFt: 35000,
        onGround: false,
        groundSpeedKts: 450,
        trackDeg: 270,
        verticalRateFpm: 0
      },
      aircraftClass: 'Medium',
      route: { callsign: 'UAL1', iataCallsign: 'UA1', origin: jfk, destination: lax },
      usingRoute: true
    }
    const f = flightFromLive(seen, t0)!
    expect(f).toMatchObject({
      label: 'UA1',
      callsign: 'UAL1',
      aircraftType: 'B39M',
      cruiseFt: 35000
    })
    expect(f.airborneFix).toMatchObject({ time: t0, lat, lon, source: 'adsb' })

    // The downloaded flight starts where the plane was seen, a takeoff and a taxi after the departure worked back.
    const e = estimate(f, t0)
    expect(e).toMatchObject({ phase: 'air', basis: 'gps' })
    expect(greatCircleKm(e.lat, e.lon, lat, lon)).toBeLessThan(1)
    expect(e.minutesIntoFlight).toBeGreaterThan(80)
    expect(e.minutesIntoFlight).toBeLessThan(130)
    expect(Math.abs(f.departure + 15 * minute - e.takeoff)).toBeLessThanOrEqual(minute / 2)

    // Seen again a minute later, further on: the same trip, so it isn't downloaded twice.
    const [lat2, lon2] = intermediate(jfk.lat, jfk.lon, lax.lat, lax.lon, 1 / 3 + 14 / 3983)
    const again = flightFromLive(
      {
        ...seen,
        time: new Date(t0 + minute).toISOString(),
        aircraft: { ...seen.aircraft!, lat: lat2, lon: lon2 }
      },
      t0 + minute
    )!
    expect(sameFlight(f, again)).toBe(true)
    expect(sameFlight(f, flight('later', lax, f.departure + 4 * 60 * minute))).toBe(false)

    // No route, or still on the ground: nothing to download.
    expect(flightFromLive({ ...seen, route: undefined }, t0)).toBeUndefined()
    expect(
      flightFromLive({ ...seen, aircraft: { ...seen.aircraft!, onGround: true } }, t0)
    ).toBeUndefined()
  })
})
