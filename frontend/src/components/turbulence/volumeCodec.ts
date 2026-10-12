import type { BinaryVolume } from '@/components/turbulence/volumeTypes'

/** Reads the backend's binary volume (Volume.toBinary): delta-coded lat/lon, then level index and EDR bytes. */
export function decodeVolume(buffer: ArrayBuffer): BinaryVolume {
  const view = new DataView(buffer)
  if (view.getUint32(0, true) !== 0x31425654) throw new Error('not a TVB1 volume')
  let at = 4
  const head = () => {
    const levels: number[] = []
    const count = view.getInt32(at, true)
    at += 4
    for (let k = 0; k < count; k++, at += 4) levels.push(view.getInt32(at, true))
    const cellKm = Math.round(view.getFloat32(at, true) * 1000) / 1000
    const n = view.getInt32(at + 4, true)
    at += 8
    return { levels, cellKm, n }
  }
  const voxels = (n: number) => {
    const out = new Int32Array(n * 4)
    for (let field = 0; field < 2; field++) {
      let prev = 0
      for (let k = 0; k < n; k++, at += 2) {
        // Deltas wrap around in 16 bits, like the backend's (a jump across the antimeridian overflows).
        prev = ((prev + view.getInt16(at, true)) << 16) >> 16
        out[k * 4 + field] = prev
      }
    }
    const bytes = new Uint8Array(buffer)
    for (let field = 2; field < 4; field++)
      for (let k = 0; k < n; k++) out[k * 4 + field] = bytes[at++]!
    return out
  }
  const gtg = head()
  const global = head()
  return {
    levelsFt: gtg.levels,
    cellKm: gtg.cellKm,
    voxels: voxels(gtg.n),
    globalLevelsFt: global.levels,
    globalCellKm: global.cellKm,
    globalVoxels: voxels(global.n)
  }
}
