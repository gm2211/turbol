import { describe, expect, it } from 'vitest'
import type { RoutePoint } from '@/api/flights'
import { type LiveFlightUpdate, keepAheadPoints } from '../follow'

const point = (minutes: number) => ({ lat: 40, lon: -100, minutesFromStart: minutes }) as RoutePoint

function update(points: RoutePoint[] | undefined): LiveFlightUpdate {
  return {
    time: '2026-10-13T12:00:00Z',
    status: 'tracking',
    aircraftClass: 'Medium',
    usingRoute: false,
    ahead: points && ({ points, segments: [], durationMinutes: 90 } as never)
  }
}

describe('keepAheadPoints', () => {
  it('passes the first update through and fills later ones that came without points', () => {
    const keep = keepAheadPoints()
    const first = keep(update([point(0), point(1)]))
    expect(first.ahead!.points).toHaveLength(2)
    const second = keep(update([]))
    expect(second.ahead!.points).toBe(first.ahead!.points)
    expect(second.ahead!.durationMinutes).toBe(90)
  })

  it('takes new points when the server sends them', () => {
    const keep = keepAheadPoints()
    keep(update([point(0)]))
    expect(keep(update([point(0), point(1), point(2)])).ahead!.points).toHaveLength(3)
    expect(keep(update([])).ahead!.points).toHaveLength(3)
  })

  it('leaves updates with no analysis alone, and keeps the points for when it returns', () => {
    const keep = keepAheadPoints()
    keep(update([point(0)]))
    expect(keep(update(undefined)).ahead).toBeUndefined()
    expect(keep(update([])).ahead!.points).toHaveLength(1)
  })

  it('gives a new stream no points of the old one', () => {
    keepAheadPoints()(update([point(0)]))
    expect(keepAheadPoints()(update([])).ahead!.points).toEqual([])
  })
})
