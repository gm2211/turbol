/**
 * A flight downloaded for offline use, and its Flight Pack: which pack is in use, which newer one is on its way, and
 * how to turn the stored slabs back into something the sampler can read.
 */
import type { Place } from '@/api/flights'
import type { AircraftClass, AircraftResponse } from '@/api/turbulence'
import type { FlyStore } from './db'
import { type PackData, type PackFrame, type PackManifest, gridOf, packFrame } from './pack'

/** A GPS reading from the phone: where it was, and how fast it was going (m/s) when the GPS says. */
export interface GpsFix {
  time: number
  lat: number
  lon: number
  speedMps?: number
  accuracyM?: number
  /** Where the fix came from when not the phone's GPS: the plane's own position, as the live map tracked it. */
  source?: 'adsb'
}

export interface SavedFlight {
  id: string
  /** What the passenger calls it: the flight number, or the route. */
  label: string
  callsign?: string
  origin: Place
  destination: Place
  /** Scheduled departure from the gate, epoch ms. */
  departure: number
  aircraftClass: AircraftClass
  aircraftType?: string
  /** How strongly the aircraft feels turbulence, from its type's wing loading; its class's when missing. */
  aircraft?: AircraftResponse
  cruiseFt?: number
  /** When the plane took off, once the passenger said so or GPS saw it. */
  takeoff?: { time: number; source: 'tap' | 'gps' }
  /** The latest GPS fix taken in the air, which the position estimate starts from. */
  airborneFix?: GpsFix
  /** The departure the pack was built for: the schedule, or the real takeoff when it was far off. */
  packDeparture: number
  /** The pack in use: every slab it lists is stored. */
  manifest?: PackManifest
  /** When the server last confirmed the pack in use is its newest. */
  checkedAt?: number
  /** A newer pack being downloaded; it replaces `manifest` once all its slabs are stored. */
  pending?: PackManifest
  savedAt: number
}

export function newFlight(f: {
  label: string
  callsign?: string
  origin: Place
  destination: Place
  departure: number
  aircraftClass: AircraftClass
  aircraftType?: string
  aircraft?: AircraftResponse
  cruiseFt?: number
}): SavedFlight {
  const id = `${f.origin.code}-${f.destination.code}-${f.departure}`
  return { ...f, id, packDeparture: f.departure, savedAt: Date.now() }
}

/**
 * Saves a flight the passenger picked. Picking one already on the phone again keeps its pack and what the passenger
 * or GPS said about its takeoff and position.
 */
export async function saveFlight(store: FlyStore, flight: SavedFlight): Promise<SavedFlight> {
  const kept = await store.updateFlight(flight.id, (old) => ({
    ...flight,
    manifest: old.manifest,
    pending: old.pending,
    checkedAt: old.checkedAt,
    takeoff: old.takeoff,
    airborneFix: old.airborneFix
  }))
  if (kept) return kept
  await store.putFlight(flight)
  return flight
}

/** The URL of the pack the server would build now for this flight. */
export function manifestUrl(f: SavedFlight): string {
  const p = (x: Place) => `${x.lat.toFixed(4)},${x.lon.toFixed(4)}`
  const dep = new Date(f.packDeparture).toISOString()
  return `/api/packs/manifest?o=${p(f.origin)}&d=${p(f.destination)}&dep=${encodeURIComponent(dep)}`
}

/** Every slab URL a saved flight still needs, in use or on its way. */
export function referencedSlabs(flights: SavedFlight[]): Set<string> {
  const urls = new Set<string>()
  for (const f of flights)
    for (const m of [f.manifest, f.pending]) for (const fr of m?.frames ?? []) urls.add(fr.url)
  return urls
}

/** The pack's frames read back from the store, or undefined while any slab is missing. */
export async function loadPack(
  store: FlyStore,
  manifest: PackManifest
): Promise<PackData | undefined> {
  const frames: PackFrame[] = []
  for (const info of manifest.frames) {
    const bytes = await store.getSlab(info.url)
    if (!bytes) return undefined
    frames.push(packFrame(info, bytes, manifest.cols))
  }
  const byTime = (kind: PackFrame['kind']) =>
    frames.filter((f) => f.kind === kind).sort((a, b) => a.validTime - b.validTime)
  return {
    grid: gridOf(manifest),
    levelsFt: manifest.levelsFt,
    nowcast: frames.find((f) => f.kind === 'nowcast'),
    forecast: byTime('forecast'),
    global: byTime('global')
  }
}
