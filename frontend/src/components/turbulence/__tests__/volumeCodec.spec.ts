import { describe, expect, it } from 'vitest'
import { decodeVolume } from '@/components/turbulence/volumeCodec'

type Point = { lat: number; lon: number; voxels: [level: number, edrHundredths: number][] }

/** Writes a volume the way the backend's Volume.toBinary2 does. */
function encode(sets: { levels: number[]; cellKm: number; points: Point[] }[]): ArrayBuffer {
  const n = sets.map((s) => s.points.reduce((a, p) => a + p.voxels.length, 0))
  const size =
    4 + sets.reduce((a, s, k) => a + 4 * (s.levels.length + 4) + 5 * s.points.length + 2 * n[k]!, 0)
  const view = new DataView(new ArrayBuffer(size))
  let at = 0
  'TVB2'.split('').forEach((c) => view.setUint8(at++, c.charCodeAt(0)))
  sets.forEach((s, k) => {
    view.setInt32(at, s.levels.length, true)
    at += 4
    for (const l of s.levels) (view.setInt32(at, l, true), (at += 4))
    view.setFloat32(at, s.cellKm, true)
    view.setInt32(at + 4, s.points.length, true)
    view.setInt32(at + 8, n[k]!, true)
    at += 12
  })
  sets.forEach((s) => {
    for (const field of ['lat', 'lon'] as const) {
      let prev = 0
      for (const p of s.points) {
        view.setInt16(at, ((p[field] - prev) << 16) >> 16, true)
        prev = p[field]
        at += 2
      }
    }
    for (const p of s.points) view.setUint8(at++, p.voxels.length)
    for (const p of s.points) {
      let prev = 0
      for (const [level] of p.voxels) (view.setUint8(at++, level - prev), (prev = level))
    }
    for (const p of s.points) for (const [, edr] of p.voxels) view.setUint8(at++, edr)
  })
  return view.buffer
}

describe('decodeVolume', () => {
  it('reads back what the backend wrote, both sets, EDR back in 200ths', () => {
    const gtg = {
      levels: [30000, 33000, 35000],
      cellKm: 18,
      points: [
        { lat: -1200, lon: -9750, voxels: [[1, 15]] },
        {
          lat: 4012,
          lon: -9744,
          voxels: [
            [0, 20],
            [2, 127]
          ]
        }
      ] as Point[]
    }
    const global = {
      levels: [35000],
      cellKm: 168,
      // Across the antimeridian: the lon delta overflows 16 bits.
      points: [
        { lat: 7950, lon: -17950, voxels: [[0, 45]] },
        { lat: 7950, lon: 17950, voxels: [[0, 46]] }
      ] as Point[]
    }
    const v = decodeVolume(encode([gtg, global]))
    expect(v.levelsFt).toEqual(gtg.levels)
    expect(v.cellKm).toBe(18)
    expect([...v.voxels]).toEqual([-1200, -9750, 1, 30, 4012, -9744, 0, 40, 4012, -9744, 2, 254])
    expect(v.globalLevelsFt).toEqual(global.levels)
    expect(v.globalCellKm).toBe(168)
    expect([...v.globalVoxels]).toEqual([7950, -17950, 0, 90, 7950, 17950, 0, 92])
  })

  it('handles an empty global layer', () => {
    const v = decodeVolume(
      encode([
        { levels: [35000], cellKm: 18, points: [{ lat: 1, lon: 2, voxels: [[0, 3]] }] },
        { levels: [], cellKm: 0, points: [] }
      ])
    )
    expect([...v.voxels]).toEqual([1, 2, 0, 6])
    expect(v.globalVoxels.length).toBe(0)
  })

  it('rejects anything else', () => {
    expect(() =>
      decodeVolume(new TextEncoder().encode('{"voxels":[]}').buffer as ArrayBuffer)
    ).toThrow()
  })
})
