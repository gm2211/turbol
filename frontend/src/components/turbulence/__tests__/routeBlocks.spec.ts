import { describe, expect, it } from 'vitest'
import { BlockIndex, routeSteps } from '@/components/turbulence/routeBlocks'
import type { ColumnBatch } from '@/components/turbulence/volumeTypes'

// An 18 km GTG block (Moderate, 34,500–36,500 ft) and a 168 km global one (Light, 36,000–38,000 ft).
const store: ColumnBatch = {
  lat: Float32Array.from([40, 50]),
  lon: Float32Array.from([-74, -30]),
  bottomFt: Float32Array.from([34500, 36000]),
  topFt: Float32Array.from([36500, 38000]),
  levelFt: Float32Array.from([35000, 37000]),
  topLevelFt: Float32Array.from([36000, 37000]),
  edr: Float32Array.from([0.2, 0.13]),
  category: Uint8Array.from([2, 1]),
  sizeKm: Float32Array.from([18, 168])
}

describe('BlockIndex', () => {
  const index = new BlockIndex(store, 2)

  it('finds the block holding a point at its altitude', () => {
    expect(index.categoryAt(40.05, -74.05, 35000)).toBe(2)
    expect(index.categoryAt(50.5, -31, 37000)).toBe(1)
  })

  it('says -1 above or below a block, and beside it', () => {
    expect(index.categoryAt(40, -74, 37000)).toBe(-1)
    expect(index.categoryAt(40, -74, 34000)).toBe(-1)
    expect(index.categoryAt(40.2, -74, 35000)).toBe(-1)
  })

  it('widens blocks in longitude away from the equator', () => {
    // 84 km half-width is ~1.17 degrees of longitude at 50N.
    expect(index.categoryAt(50, -31.1, 37000)).toBe(1)
    expect(index.categoryAt(50, -31.3, 37000)).toBe(-1)
  })
})

describe('routeSteps', () => {
  it('cuts each leg into steps no longer than asked, interpolating altitude', () => {
    const steps = routeSteps(
      [
        { lat: 40, lon: -74, altitudeFt: 30000 },
        { lat: 40, lon: -73.9, altitudeFt: 32000 }
      ],
      3
    )
    // ~8.5 km apart: three steps.
    expect(steps).toHaveLength(3)
    expect(steps[0]!.from).toEqual([-74, 40, 30000])
    expect(steps[2]!.to[2]).toBeCloseTo(32000)
    expect(steps.every((s) => s.point === 0)).toBe(true)
  })
})
