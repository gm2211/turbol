import type { BinaryVolume } from '@/components/turbulence/volumeTypes'

/**
 * Reads the backend's binary volume (Volume.toBinary2): grid points with delta-coded lat/lon and a voxel count, then
 * each voxel's level step and EDR in hundredths. Voxels come out grouped by point, bottom up.
 */
export function decodeVolume(buffer: ArrayBuffer): BinaryVolume {
  const view = new DataView(buffer)
  if (view.getUint32(0, true) !== 0x32425654) throw new Error('not a TVB2 volume')
  const bytes = new Uint8Array(buffer)
  let at = 4
  const head = () => {
    const levels: number[] = []
    const count = view.getInt32(at, true)
    at += 4
    for (let k = 0; k < count; k++, at += 4) levels.push(view.getInt32(at, true))
    const cellKm = Math.round(view.getFloat32(at, true) * 1000) / 1000
    const points = view.getInt32(at + 4, true)
    const n = view.getInt32(at + 8, true)
    at += 12
    return { levels, cellKm, points, n }
  }
  const voxels = ({ points, n }: { points: number; n: number }) => {
    const lat = new Int32Array(points)
    const lon = new Int32Array(points)
    for (const out of [lat, lon]) {
      let prev = 0
      for (let p = 0; p < points; p++, at += 2) {
        // Deltas wrap around in 16 bits, like the backend's (a jump across the antimeridian overflows).
        prev = ((prev + view.getInt16(at, true)) << 16) >> 16
        out[p] = prev
      }
    }
    const out = new Int32Array(n * 4)
    let levelAt = at + points
    const edrAt = levelAt + n
    let k = 0
    for (let p = 0; p < points; p++) {
      let level = 0
      for (let c = bytes[at + p]!; c > 0; c--, k++) {
        level += bytes[levelAt++]!
        out[k * 4] = lat[p]!
        out[k * 4 + 1] = lon[p]!
        out[k * 4 + 2] = level
        // Back to EDR * 200, the unit the rest of the 3D code reads.
        out[k * 4 + 3] = bytes[edrAt + k]! * 2
      }
    }
    at = edrAt + n
    return out
  }
  const gtg = head()
  const global = head()
  return {
    levelsFt: gtg.levels,
    cellKm: gtg.cellKm,
    voxels: voxels(gtg),
    globalLevelsFt: global.levels,
    globalCellKm: global.cellKm,
    globalVoxels: voxels(global)
  }
}
