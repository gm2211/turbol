import { describe, expect, it } from 'vitest'
import type { RoutePoint } from '@/api/flights'
import {
  type Corridor,
  bestWhatIf,
  corridorCategory,
  minutesByCategory,
  noChange,
  roughMinutes,
  whatIfPath
} from '@/components/turbulence/corridor'

const t = { light: 0.12, moderate: 0.14, severe: 0.44, extreme: 0.79 }
const levelsFt = [-2000, -1000, 0, 1000, 2000]
const offsetsKm = [-15, 0, 15]

// 9 points 100 km apart, 10 minutes apart, at FL350. Moderate (EDR 0.2) at and below the flight's level from point 3
// to 5, smooth (0.05) everywhere else.
const points = Array.from(
  { length: 9 },
  (_, k) =>
    ({
      lat: 50,
      lon: -30 + k * 1.4,
      distanceKm: k * 100,
      altitudeFt: 35000,
      minutesFromStart: k * 10,
      time: new Date(k * 600_000).toISOString()
    }) as RoutePoint
)
const edr = new Uint8Array(points.length * levelsFt.length * offsetsKm.length)
points.forEach((_, k) =>
  levelsFt.forEach((lev, l) =>
    offsetsKm.forEach((_, o) => {
      edr[(k * levelsFt.length + l) * offsetsKm.length + o] = k >= 3 && k <= 5 && lev <= 0 ? 40 : 10
    })
  )
)
const corridor: Corridor = { levelsFt, offsetsKm, points: points.length, edr }

describe('corridor', () => {
  it('classifies a cell as felt by the aircraft', () => {
    expect(corridorCategory(corridor, 4, 2, 1, 1, t)).toBe(2)
    // A heavy jet feeling 0.6 of it: 0.12, light.
    expect(corridorCategory(corridor, 4, 2, 1, 0.6, t)).toBe(1)
  })

  it('eases a climb in after takeoff and out before landing', () => {
    const path = whatIfPath(corridor, points, { shiftFt: 2000, offsetKm: 0 })
    expect(path.map((p) => levelsFt[p.level])).toEqual([
      0, 1000, 1000, 2000, 2000, 2000, 1000, 1000, 0
    ])
    expect(path[4]!.altitudeFt).toBe(37000)
  })

  it('counts the minutes at each category along a path', () => {
    const planned = minutesByCategory(
      corridor,
      points,
      whatIfPath(corridor, points, noChange),
      1,
      t
    )
    expect(roughMinutes(planned)).toBe(30)
    const higher = whatIfPath(corridor, points, { shiftFt: 1000, offsetKm: 0 })
    expect(roughMinutes(minutesByCategory(corridor, points, higher, 1, t))).toBe(0)
  })

  it('finds the smallest change that avoids the rough air', () => {
    expect(bestWhatIf(corridor, points, 1, t).whatIf).toEqual({ shiftFt: 1000, offsetKm: 0 })
  })
})
