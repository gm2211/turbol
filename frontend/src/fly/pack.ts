/**
 * A Flight Pack on the phone: the raw EDR for a flight's corridor that the backend's PackEndpoint serves, and the
 * sampler that answers "how rough is it here, at this altitude and time" from it with no network. The sampler is the
 * backend's TurbulenceStore.sampleAt over pack cells instead of HRRR grid points: the GTGN nowcast wins near its
 * valid time and tapers into the GTG forecast, which is interpolated between forecast hours and between levels.
 */
import type { AircraftClass, Thresholds } from '@/api/turbulence'
import type { TurbulenceSource } from './analyzer'
import { PackGrid } from './geo'

export interface PackFrameInfo {
  id: string
  kind: 'nowcast' | 'forecast'
  validTime: string
  issued: string
  rowStart: number
  rowEnd: number
  url: string
}

export interface PackManifest {
  version: number
  origin: { lat: number; lon: number }
  destination: { lat: number; lon: number }
  departure: string
  totalKm: number
  rows: number
  cols: number
  alongStepKm: number
  crossStepKm: number
  levelsFt: number[]
  frames: PackFrameInfo[]
  nowcastValid?: string
  forecastIssued?: string
  forecastUntil?: string
  thresholds: Record<AircraftClass, Thresholds>
  generatedAt: string
}

/** One frame's slab, decoded: EDR codes laid out [level][row - rowStart][col]. */
export interface PackFrame {
  id: string
  kind: 'nowcast' | 'forecast'
  /** Epoch ms. */
  validTime: number
  issued: number
  rowStart: number
  rowEnd: number
  codes: Uint8Array
}

/** What the sampler reads: one nowcast at most and the forecast hours in time order, as on the server. */
export interface PackData {
  grid: PackGrid
  levelsFt: number[]
  nowcast?: PackFrame
  forecast: PackFrame[]
}

export type EdrSource = 'Nowcast' | 'Forecast' | 'Blend' | 'NoData'

export interface EdrSample {
  edr: number
  source: EdrSource
}

const missing = 255
const noData: EdrSample = { edr: NaN, source: 'NoData' }

/** The grid a manifest describes. Throws when the phone and server would lay the cells out differently. */
export function gridOf(m: PackManifest): PackGrid {
  const halfWidthKm = ((m.cols - 1) / 2) * m.crossStepKm
  const { origin: o, destination: d } = m
  const grid = new PackGrid(o.lat, o.lon, d.lat, d.lon, 6.0, m.crossStepKm, halfWidthKm)
  if (grid.rows !== m.rows || grid.cols !== m.cols) {
    throw new Error(
      `Pack grid mismatch: ${grid.rows}x${grid.cols} here, ${m.rows}x${m.cols} on the server`
    )
  }
  return grid
}

/** Undo the backend's delta coding along the route (FlightPack.encode), in place. */
export function decodeSlab(bytes: Uint8Array, rowCount: number, cols: number): Uint8Array {
  const perLevel = rowCount * cols
  const levels = Math.floor(bytes.length / Math.max(1, perLevel))
  for (let li = 0; li < levels; li++) {
    const base = li * perLevel
    for (let r = 1; r < rowCount; r++) {
      const row = base + r * cols
      for (let c = 0; c < cols; c++)
        bytes[row + c] = (bytes[row + c] + bytes[row - cols + c]) & 0xff
    }
  }
  return bytes
}

export function packFrame(info: PackFrameInfo, slab: Uint8Array, cols: number): PackFrame {
  return {
    id: info.id,
    kind: info.kind,
    validTime: Date.parse(info.validTime),
    issued: Date.parse(info.issued),
    rowStart: info.rowStart,
    rowEnd: info.rowEnd,
    codes: decodeSlab(slab, info.rowEnd - info.rowStart, cols)
  }
}

/** The pack as something the route analyzer can sample. */
export function packSource(data: PackData): TurbulenceSource {
  return {
    sample: (lat, lon, altitudeFt, time) => sample(data, lat, lon, altitudeFt, time),
    nowcastValid: data.nowcast?.validTime,
    forecastIssued: data.forecast[0]?.issued,
    forecastUntil: data.forecast[data.forecast.length - 1]?.validTime
  }
}

/** EDR at a location, altitude and time from the pack, or NaN / NoData outside it. */
export function sample(
  data: PackData,
  lat: number,
  lon: number,
  altitudeFt: number,
  time: number
): EdrSample {
  const cell = data.grid.cellOf(lat, lon)
  return cell ? sampleCell(data, cell[0], cell[1], altitudeFt, time) : noData
}

export function sampleCell(
  data: PackData,
  row: number,
  col: number,
  altitudeFt: number,
  time: number
): EdrSample {
  const weight = data.nowcast ? nowcastWeight(data.nowcast.validTime, time) : 0
  const nowcastEdr =
    weight > 0 && data.nowcast ? edrAt(data, data.nowcast, row, col, altitudeFt) : NaN
  const forecastEdr = forecastAt(data, row, col, altitudeFt, time)
  if (!Number.isNaN(nowcastEdr) && !Number.isNaN(forecastEdr)) {
    return weight >= 1.0
      ? { edr: nowcastEdr, source: 'Nowcast' }
      : { edr: weight * nowcastEdr + (1 - weight) * forecastEdr, source: 'Blend' }
  }
  if (!Number.isNaN(nowcastEdr)) return { edr: nowcastEdr, source: 'Nowcast' }
  if (!Number.isNaN(forecastEdr)) return { edr: forecastEdr, source: 'Forecast' }
  return noData
}

/** Whole seconds from a to b, as java.time.Duration.toSeconds. */
const secondsBetween = (a: number, b: number) => Math.floor((b - a) / 1000)

/** 1 at (and up to 2 h before) the nowcast's valid time, tapering to 0 one hour after it. */
export function nowcastWeight(nowcastValid: number, time: number): number {
  const hours = secondsBetween(nowcastValid, time) / 3600.0
  if (hours < -2.0) return 0.0
  if (hours <= 0) return 1.0
  return Math.max(0.0, 1.0 - hours / 1.0)
}

/** Forecast EDR linearly interpolated in time between the two bracketing forecast hours. */
function forecastAt(
  data: PackData,
  row: number,
  col: number,
  altitudeFt: number,
  time: number
): number {
  const frames = data.forecast
  const first = frames[0]
  const last = frames[frames.length - 1]
  if (!first || !last || time > last.validTime) return NaN
  if (!(time > first.validTime)) {
    // Before the first forecast hour (F001): fine within the hour since the cycle started.
    return Math.floor(secondsBetween(time, first.validTime) / 60) <= 90
      ? edrAt(data, first, row, col, altitudeFt)
      : NaN
  }
  const hi = frames.findIndex((f) => !(f.validTime < time))
  const after = frames[hi]
  const before = frames[hi - 1]
  const span = secondsBetween(before.validTime, after.validTime)
  const w = secondsBetween(before.validTime, time) / span
  const a = edrAt(data, before, row, col, altitudeFt)
  const b = edrAt(data, after, row, col, altitudeFt)
  if (Number.isNaN(a)) return b
  if (Number.isNaN(b)) return a
  return a + w * (b - a)
}

/** EDR linearly interpolated between the two nearest levels; NaN where the slab has no data. */
function edrAt(
  data: PackData,
  frame: PackFrame,
  row: number,
  col: number,
  altitudeFt: number
): number {
  const levels = data.levelsFt
  if (row < frame.rowStart || row >= frame.rowEnd || levels.length === 0) return NaN
  // The levels at or below the altitude truncated to whole feet, and the first one above it (EdrFrame.edrAtIndex).
  const key = Math.trunc(altitudeFt) + 1
  let lo = -1
  while (lo + 1 < levels.length && levels[lo + 1] < key) lo++
  const hi = lo + 1 < levels.length ? lo + 1 : -1
  const rowCount = frame.rowEnd - frame.rowStart
  const cols = data.grid.cols
  const value = (li: number) => {
    const code = frame.codes[(li * rowCount + (row - frame.rowStart)) * cols + col]
    return code === missing ? NaN : code / 200.0
  }
  if (lo >= 0 && hi >= 0) {
    const a = value(lo)
    const b = value(hi)
    if (Number.isNaN(a)) return b
    if (Number.isNaN(b)) return a
    const w = (altitudeFt - levels[lo]) / (levels[hi] - levels[lo])
    return a + w * (b - a)
  }
  if (lo >= 0) return value(lo)
  if (hi >= 0) return value(hi)
  return NaN
}
