import { describe, expect, it } from 'vitest'
import { VolumeProfiles, levelNear } from '@/components/turbulence/volumeProfile'

// Two GTG points (one with a 3-level stack) and one global point, 4 ints per voxel as decodeVolume gives them.
const volume = {
  levelsFt: [33000, 34000, 35000, 36000],
  cellKm: 18,
  voxels: Int32Array.from([
    3900, -10000, 0, 24, 3900, -10000, 1, 30, 3900, -10000, 2, 26, 3906, -10000, 3, 100
  ]),
  globalLevelsFt: [35000, 37000],
  globalCellKm: 166.8,
  globalVoxels: Int32Array.from([5000, -3000, 1, 40])
}

describe('VolumeProfiles', () => {
  const profiles = new VolumeProfiles(volume)

  it("gives a stacked point's levels bottom up with their own EDR", () => {
    expect(profiles.at(39, -100, 18)).toEqual({
      levelsFt: [33000, 34000, 35000],
      edr: [0.12, 0.15, 0.13]
    })
  })

  it('keeps GTG and the global layer apart by block size', () => {
    expect(profiles.at(50, -30, 166.8)).toEqual({ levelsFt: [37000], edr: [0.2] })
    expect(profiles.at(50, -30, 18)).toBeUndefined()
  })

  it('picks the level nearest the pointer, inside the shown band', () => {
    const p = profiles.at(39, -100, 18)!
    expect(levelNear(p, 34400, 0, 45000)).toEqual({ levelFt: 34000, edr: 0.15 })
    expect(levelNear(p, 34800, 0, 45000)).toEqual({ levelFt: 35000, edr: 0.13 })
    expect(levelNear(p, 35000, 0, 34000)).toEqual({ levelFt: 34000, edr: 0.15 })
  })
})
