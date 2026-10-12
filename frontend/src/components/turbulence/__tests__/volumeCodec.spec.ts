import { describe, expect, it } from 'vitest'
import { decodeVolume } from '@/components/turbulence/volumeCodec'

/** Writes a volume the way the backend's Volume.toBinary does. */
function encode(sets: { levels: number[]; cellKm: number; voxels: number[] }[]): ArrayBuffer {
  const n = sets.map((s) => s.voxels.length / 4)
  const size = 4 + sets.reduce((a, s, k) => a + 4 * (s.levels.length + 3) + 6 * n[k]!, 0)
  const view = new DataView(new ArrayBuffer(size))
  let at = 0
  'TVB1'.split('').forEach((c) => view.setUint8(at++, c.charCodeAt(0)))
  sets.forEach((s, k) => {
    view.setInt32(at, s.levels.length, true)
    at += 4
    for (const l of s.levels) (view.setInt32(at, l, true), (at += 4))
    view.setFloat32(at, s.cellKm, true)
    view.setInt32(at + 4, n[k]!, true)
    at += 8
  })
  sets.forEach((s, k) => {
    for (const field of [0, 1]) {
      let prev = 0
      for (let i = 0; i < n[k]!; i++, at += 2) {
        view.setInt16(at, ((s.voxels[i * 4 + field]! - prev) << 16) >> 16, true)
        prev = s.voxels[i * 4 + field]!
      }
    }
    for (const field of [2, 3])
      for (let i = 0; i < n[k]!; i++) view.setUint8(at++, s.voxels[i * 4 + field]!)
  })
  return view.buffer
}

describe('decodeVolume', () => {
  it('reads back what the backend wrote, both sets', () => {
    const gtg = {
      levels: [30000, 35000],
      cellKm: 18,
      voxels: [4012, -9750, 0, 40, 4015, -9744, 1, 254, -1200, 17999, 1, 30]
    }
    const global = {
      levels: [35000],
      cellKm: 168,
      // Across the antimeridian: the lon delta overflows 16 bits.
      voxels: [7950, -17950, 0, 90, 7950, 17950, 0, 91, -7950, -17950, 0, 92]
    }
    const v = decodeVolume(encode([gtg, global]))
    expect(v.levelsFt).toEqual(gtg.levels)
    expect(v.cellKm).toBe(18)
    expect([...v.voxels]).toEqual(gtg.voxels)
    expect(v.globalLevelsFt).toEqual(global.levels)
    expect(v.globalCellKm).toBe(168)
    expect([...v.globalVoxels]).toEqual(global.voxels)
  })

  it('handles an empty global layer', () => {
    const v = decodeVolume(
      encode([
        { levels: [35000], cellKm: 18, voxels: [1, 2, 0, 3] },
        { levels: [], cellKm: 0, voxels: [] }
      ])
    )
    expect([...v.voxels]).toEqual([1, 2, 0, 3])
    expect(v.globalVoxels.length).toBe(0)
  })

  it('rejects anything else', () => {
    expect(() =>
      decodeVolume(new TextEncoder().encode('{"voxels":[]}').buffer as ArrayBuffer)
    ).toThrow()
  })
})
