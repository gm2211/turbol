import { describe, expect, it } from 'vitest'
import { build } from '@/components/turbulence/volumeBuild'
import type { BinaryVolume } from '@/components/turbulence/volumeTypes'

const t = { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 }
const volume = (voxels: number[]): BinaryVolume => ({
  levelsFt: [30000, 33000, 35000],
  cellKm: 18,
  voxels: Int32Array.from(voxels),
  globalLevelsFt: [],
  globalCellKm: 0,
  globalVoxels: new Int32Array()
})

describe('build', () => {
  it('stacks adjacent levels of one category at a point into one column, nearest first', () => {
    // As the backend writes them: level by level. EDR bytes are EDR * 200 (26 = 0.13 Light, 40 = 0.2 Moderate).
    const { cols, byDistance } = build(
      volume([4000, -9000, 0, 26, 3000, -8000, 0, 40, 4000, -9000, 1, 26, 4000, -9000, 2, 40]),
      t,
      [-80, 30]
    )
    expect(cols.length).toBe(3)
    const rows = [...byDistance].map((i) => [
      cols.lat[i],
      cols.lon[i],
      cols.bottomFt[i],
      cols.topFt[i],
      cols.levelFt[i],
      cols.topLevelFt[i],
      cols.category[i]
    ])
    expect(rows).toEqual([
      [30, -80, 29000, 31500, 30000, 30000, 2],
      [40, -90, 29000, 34000, 30000, 33000, 1],
      [40, -90, 34000, 36000, 35000, 35000, 2]
    ])
  })
})
