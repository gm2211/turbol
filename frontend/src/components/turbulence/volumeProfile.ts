import type { BinaryVolume } from '@/components/turbulence/volumeTypes'

/** One grid point's voxels, bottom up: each level in feet and its EDR. */
export interface PointProfile {
  levelsFt: number[]
  edr: number[]
}

/**
 * Every grid point's per-level EDR in a decoded volume, so the 3D tooltip can name the single level under the pointer
 * inside a stacked column (which only keeps its worst EDR). Built on the first hover, not at load.
 */
export class VolumeProfiles {
  private readonly sets: {
    levels: number[]
    cellKm: number
    vox: Int32Array
    start: Map<number, number>
  }[]

  constructor(v: BinaryVolume) {
    this.sets = [
      { levels: v.levelsFt, cellKm: v.cellKm, vox: v.voxels, start: new Map() },
      { levels: v.globalLevelsFt, cellKm: v.globalCellKm, vox: v.globalVoxels, start: new Map() }
    ]
    for (const s of this.sets) {
      // Voxels come grouped by point (see decodeVolume): remember where each point's run starts.
      for (let k = 0; k < s.vox.length / 4; k++) {
        const key = pointKey(s.vox[k * 4]!, s.vox[k * 4 + 1]!)
        if (!s.start.has(key)) s.start.set(key, k)
      }
    }
  }

  /** The point at `lat`, `lon` (degrees) in the set whose blocks are `sizeKm` wide. */
  at(lat: number, lon: number, sizeKm: number): PointProfile | undefined {
    const s = this.sets.find((set) => Math.abs(set.cellKm - sizeKm) < 1e-3)
    if (!s) return undefined
    const la = Math.round(lat * 100)
    const lo = Math.round(lon * 100)
    const first = s.start.get(pointKey(la, lo))
    if (first === undefined) return undefined
    const out: PointProfile = { levelsFt: [], edr: [] }
    for (
      let k = first;
      k < s.vox.length / 4 && s.vox[k * 4] === la && s.vox[k * 4 + 1] === lo;
      k++
    ) {
      out.levelsFt.push(s.levels[s.vox[k * 4 + 2]!]!)
      out.edr.push(s.vox[k * 4 + 3]! / 200)
    }
    return out
  }
}

const pointKey = (lat100: number, lon100: number) => (lat100 + 9000) * 36001 + (lon100 + 18000)

/** The level of `profile` within [lo, hi] (feet) nearest `altitudeFt`, and its EDR. */
export function levelNear(profile: PointProfile, altitudeFt: number, lo: number, hi: number) {
  let best = -1
  profile.levelsFt.forEach((l, k) => {
    if (l < lo || l > hi) return
    if (best < 0 || Math.abs(l - altitudeFt) < Math.abs(profile.levelsFt[best]! - altitudeFt))
      best = k
  })
  return best < 0 ? undefined : { levelFt: profile.levelsFt[best]!, edr: profile.edr[best]! }
}
