import { describe, expect, it } from 'vitest'
import {
  backoffPollMs,
  createTrafficPoller,
  fastModeMaxMs,
  fastPollMs,
  normalPollMs
} from '../trafficPoll'

describe('createTrafficPoller', () => {
  it('polls a complete view at the normal rate', () => {
    expect(createTrafficPoller().next(true, 0)).toBe(normalPollMs)
  })

  it('polls an incomplete view no faster than every 10 s, then backs off to 30 s', () => {
    const p = createTrafficPoller()
    let t = 0
    const delays: number[] = []
    for (let i = 0; i < 3; i++) {
      const d = p.next(false, t)
      delays.push(d)
      t += d
    }
    expect(delays).toEqual([fastPollMs, fastPollMs, backoffPollMs])
    expect(Math.min(...delays)).toBeGreaterThanOrEqual(10_000)
  })

  it('gives up on fast mode after a minute and goes back to the normal rate', () => {
    const p = createTrafficPoller()
    let t = 0
    const delays: number[] = []
    // Every poll comes back incomplete.
    while (delays.length < 10 && delays.at(-1) !== normalPollMs) {
      const d = p.next(false, t)
      delays.push(d)
      t += d
    }
    expect(delays).toEqual([fastPollMs, fastPollMs, backoffPollMs, backoffPollMs, normalPollMs])
    // The switch back came on the first poll at or after a minute.
    expect(t - normalPollMs).toBeGreaterThanOrEqual(fastModeMaxMs)
    // Still incomplete later: stays at the normal rate.
    expect(p.next(false, t + 1000)).toBe(normalPollMs)
  })

  it('starts over when the view completes or is reset', () => {
    const p = createTrafficPoller()
    p.next(false, 0)
    p.next(false, 10_000)
    p.next(false, 20_000)
    expect(p.next(true, 50_000)).toBe(normalPollMs)
    expect(p.next(false, 51_000)).toBe(fastPollMs)
    p.next(false, 61_000)
    p.next(false, 71_000)
    p.reset()
    expect(p.next(false, 200_000)).toBe(fastPollMs)
  })
})
