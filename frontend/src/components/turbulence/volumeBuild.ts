import type { Thresholds } from '@/api/turbulence'
import type { BinaryVolume, ColumnBatch } from '@/components/turbulence/volumeTypes'

function classify(edr: number, t: Thresholds): number {
  if (edr < t.light) return 0
  if (edr < t.moderate) return 1
  if (edr < t.severe) return 2
  if (edr < t.extreme) return 3
  return 4
}

type Columns = { [K in keyof ColumnBatch]: ColumnBatch[K] } & { length: number }

/** Room for `n` columns. */
function columns(n: number): Columns {
  return {
    lat: new Float32Array(n),
    lon: new Float32Array(n),
    bottomFt: new Float32Array(n),
    topFt: new Float32Array(n),
    levelFt: new Float32Array(n),
    topLevelFt: new Float32Array(n),
    edr: new Float32Array(n),
    category: new Uint8Array(n),
    sizeKm: new Float32Array(n),
    length: 0
  }
}

// Sort keys pack a value and an index (below 2^20) into one float64, so a plain numeric typed-array sort (no
// comparator calls: ~10x faster on a few hundred thousand cells) gives the order, and ties keep index order.
const indexBits = 2 ** 20

/** Stack one voxel set (GTG's, or the global layer's) into columns of `sizeKm` blocks. */
function stack(
  cols: Columns,
  vox: ArrayLike<number>,
  levels: number[],
  sizeKm: number,
  t: Thresholds
) {
  const n = vox.length / 4
  // Cells by grid point, then level (the backend writes them level by level, so index order is level order), so
  // each point's stack can be walked bottom-up.
  const order = new Uint32Array(n)
  if (n < indexBits) {
    const keys = new Float64Array(n)
    for (let i = 0; i < n; i++)
      keys[i] = ((vox[i * 4]! + 9000) * 36001 + vox[i * 4 + 1]! + 18000) * indexBits + i
    keys.sort()
    for (let i = 0; i < n; i++) order[i] = keys[i]! % indexBits
  } else {
    for (let i = 0; i < n; i++) order[i] = i
    order.sort(
      (a, b) =>
        vox[a * 4]! - vox[b * 4]! ||
        vox[a * 4 + 1]! - vox[b * 4 + 1]! ||
        vox[a * 4 + 2]! - vox[b * 4 + 2]!
    )
  }
  let last = -1
  let lastLat = NaN
  let lastLon = NaN
  let lastTop = NaN
  for (let k = 0; k < n; k++) {
    const i = order[k]!
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
      lastLat === lat &&
      lastLon === lon &&
      cols.category[last] === cat &&
      lastTop === bottom
    ) {
      cols.topFt[last] = top
      cols.topLevelFt[last] = level
      cols.edr[last] = Math.max(cols.edr[last]!, edr)
    } else {
      last = cols.length++
      cols.lat[last] = lat / 100
      cols.lon[last] = lon / 100
      cols.bottomFt[last] = bottom
      cols.topFt[last] = top
      cols.levelFt[last] = level
      cols.topLevelFt[last] = level
      cols.edr[last] = edr
      cols.category[last] = cat
      cols.sizeKm[last] = sizeKm
      lastLat = lat
      lastLon = lon
    }
    lastTop = top
  }
}

export function build(v: BinaryVolume, t: Thresholds, center: [number, number]) {
  const cols = columns(v.voxels.length / 4 + v.globalVoxels.length / 4)
  stack(cols, v.voxels, v.levelsFt, v.cellKm, t)
  if (v.globalVoxels.length) stack(cols, v.globalVoxels, v.globalLevelsFt, v.globalCellKm, t)

  // Nearest to the camera first (distance in 0.001 square degrees).
  const m = cols.length
  const cosLat = Math.cos((center[1] * Math.PI) / 180)
  const byDistance = new Uint32Array(m)
  if (m < indexBits) {
    const keys = new Float64Array(m)
    for (let i = 0; i < m; i++) {
      const dx = (cols.lon[i]! - center[0]) * cosLat
      const dy = cols.lat[i]! - center[1]
      keys[i] = Math.round((dx * dx + dy * dy) * 1000) * indexBits + i
    }
    keys.sort()
    for (let i = 0; i < m; i++) byDistance[i] = keys[i]! % indexBits
  } else {
    const dist = new Float32Array(m)
    for (let i = 0; i < m; i++) {
      const dx = (cols.lon[i]! - center[0]) * cosLat
      const dy = cols.lat[i]! - center[1]
      dist[i] = dx * dx + dy * dy
    }
    for (let i = 0; i < m; i++) byDistance[i] = i
    byDistance.sort((a, b) => dist[a]! - dist[b]!)
  }
  return { cols, byDistance }
}
