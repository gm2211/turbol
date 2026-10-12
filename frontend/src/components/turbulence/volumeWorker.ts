/// <reference lib="webworker" />
/**
 * Prepares a frame's turbulence volume (downloaded by prefetchVolume) off the main thread, so opening 3D never freezes the page.
 * Cells are classified, stacked into same-category columns (see mergeColumns in scene3d.ts) and sent back nearest
 * to the camera first, in a few batches of transferable typed arrays, so the scene fills in from the middle out.
 */
import type { Thresholds } from '@/api/turbulence'
import type {
  BinaryVolume,
  ColumnBatch,
  VolumeMessage,
  VolumeRequest
} from '@/components/turbulence/volumeTypes'
import { decodeVolume } from '@/components/turbulence/volumeCodec'

const post = (m: VolumeMessage, transfer: Transferable[] = []) => self.postMessage(m, transfer)

function classify(edr: number, t: Thresholds): number {
  if (edr < t.light) return 0
  if (edr < t.moderate) return 1
  if (edr < t.severe) return 2
  if (edr < t.extreme) return 3
  return 4
}

type Columns = Record<keyof ColumnBatch, number[]>

/** Stack one voxel set (GTG's, or the global layer's) into columns of `sizeKm` blocks. */
function stack(
  cols: Columns,
  vox: ArrayLike<number>,
  levels: number[],
  sizeKm: number,
  t: Thresholds
) {
  const n = vox.length / 4
  // Sort cells by grid point, then level, so each point's stack can be walked bottom-up.
  const order = new Uint32Array(n)
  for (let i = 0; i < n; i++) order[i] = i
  order.sort(
    (a, b) =>
      vox[a * 4]! - vox[b * 4]! ||
      vox[a * 4 + 1]! - vox[b * 4 + 1]! ||
      vox[a * 4 + 2]! - vox[b * 4 + 2]!
  )
  let last = -1
  for (const i of order) {
    const lat = vox[i * 4]!
    const lon = vox[i * 4 + 1]!
    const li = vox[i * 4 + 2]!
    const level = levels[li]!
    const prev = levels[li - 1]
    const next = levels[li + 1]
    const bottom = prev === undefined ? Math.max(level - 1000, 0) : (prev + level) / 2
    const top = next === undefined ? level + 1000 : (next + level) / 2
    const edr = vox[i * 4 + 3]! / 200
    const cat = classify(edr, t)
    if (
      last >= 0 &&
      cols.lat[last] === lat / 100 &&
      cols.lon[last] === lon / 100 &&
      cols.category[last] === cat &&
      cols.topFt[last] === bottom
    ) {
      cols.topFt[last] = top
      cols.topLevelFt[last] = level
      cols.edr[last] = Math.max(cols.edr[last]!, edr)
    } else {
      cols.lat.push(lat / 100)
      cols.lon.push(lon / 100)
      cols.bottomFt.push(bottom)
      cols.topFt.push(top)
      cols.levelFt.push(level)
      cols.topLevelFt.push(level)
      cols.edr.push(edr)
      cols.category.push(cat)
      cols.sizeKm.push(sizeKm)
      last = cols.lat.length - 1
    }
  }
}

function build(v: BinaryVolume, t: Thresholds, center: [number, number]) {
  const cols: Columns = {
    lat: [],
    lon: [],
    bottomFt: [],
    topFt: [],
    levelFt: [],
    topLevelFt: [],
    edr: [],
    category: [],
    sizeKm: []
  }
  stack(cols, v.voxels, v.levelsFt, v.cellKm, t)
  if (v.globalVoxels.length) stack(cols, v.globalVoxels, v.globalLevelsFt, v.globalCellKm, t)

  // Nearest to the camera first.
  const m = cols.lat.length
  const cosLat = Math.cos((center[1] * Math.PI) / 180)
  const dist = new Float32Array(m)
  for (let i = 0; i < m; i++) {
    const dx = (cols.lon[i]! - center[0]) * cosLat
    const dy = cols.lat[i]! - center[1]
    dist[i] = dx * dx + dy * dy
  }
  const byDistance = new Uint32Array(m)
  for (let i = 0; i < m; i++) byDistance[i] = i
  byDistance.sort((a, b) => dist[a]! - dist[b]!)
  return { cols, byDistance }
}

self.onmessage = (e: MessageEvent<VolumeRequest>) => {
  try {
    const v = decodeVolume(e.data.volume)
    const { cols, byDistance } = build(v, e.data.thresholds, e.data.center)
    const total = byDistance.length
    post({ type: 'meta', cellKm: v.cellKm, total })
    // A small first batch paints quickly; later ones grow.
    let start = 0
    let size = 8000
    while (start < total) {
      const idx = byDistance.subarray(start, Math.min(start + size, total))
      const pick = <T extends Float32Array | Uint8Array>(
        src: number[],
        Out: new (n: number) => T
      ): T => {
        const out = new Out(idx.length)
        for (let j = 0; j < idx.length; j++) out[j] = src[idx[j]!]!
        return out
      }
      const batch: ColumnBatch = {
        lat: pick(cols.lat, Float32Array),
        lon: pick(cols.lon, Float32Array),
        bottomFt: pick(cols.bottomFt, Float32Array),
        topFt: pick(cols.topFt, Float32Array),
        levelFt: pick(cols.levelFt, Float32Array),
        topLevelFt: pick(cols.topLevelFt, Float32Array),
        edr: pick(cols.edr, Float32Array),
        category: pick(cols.category, Uint8Array),
        sizeKm: pick(cols.sizeKm, Float32Array)
      }
      post(
        { type: 'batch', batch },
        Object.values(batch).map((a) => a.buffer)
      )
      start += size
      size *= 2
    }
    post({ type: 'done' })
  } catch (err) {
    post({ type: 'error', message: String(err) })
  }
}
