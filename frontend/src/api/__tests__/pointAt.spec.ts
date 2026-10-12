import { describe, expect, it } from 'vitest'
import { type RoutePoint, pointAt } from '@/api/flights'

const at = (minutesFromStart: number) => ({ minutesFromStart }) as RoutePoint

describe('pointAt', () => {
  const points = [at(0), at(10), at(20), at(30)]

  it('picks the sample nearest the minute', () => {
    expect(pointAt(points, 14)?.minutesFromStart).toBe(10)
    expect(pointAt(points, 16)?.minutesFromStart).toBe(20)
  })

  it('clamps to the ends', () => {
    expect(pointAt(points, -5)?.minutesFromStart).toBe(0)
    expect(pointAt(points, 99)?.minutesFromStart).toBe(30)
  })

  it('has nothing to read on an empty route', () => {
    expect(pointAt([], 5)).toBeUndefined()
  })
})
