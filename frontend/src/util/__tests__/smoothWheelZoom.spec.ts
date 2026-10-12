import { describe, expect, it } from 'vitest'
import { wheelZoomDelta } from '@/util/smoothWheelZoom'

const PIXEL = 0
const LINE = 1

describe('wheelZoomDelta', () => {
  it('zooms one level per mouse notch, in on wheel up and out on wheel down', () => {
    expect(wheelZoomDelta({ deltaY: -100, deltaMode: PIXEL, ctrlKey: false })).toBeCloseTo(1)
    expect(wheelZoomDelta({ deltaY: 100, deltaMode: PIXEL, ctrlKey: false })).toBeCloseTo(-1)
  })

  it('reads line deltas (Firefox) as 40 px a line', () => {
    expect(wheelZoomDelta({ deltaY: -3, deltaMode: LINE, ctrlKey: false })).toBeCloseTo(1.2)
  })

  it('follows a trackpad pinch one to one: deltaY = -100 ln(scale)', () => {
    const doubled = -100 * Math.log(2)
    expect(wheelZoomDelta({ deltaY: doubled, deltaMode: PIXEL, ctrlKey: true })).toBeCloseTo(1)
  })

  it('never asks for more than two levels from one event', () => {
    expect(wheelZoomDelta({ deltaY: -5000, deltaMode: PIXEL, ctrlKey: false })).toBe(2)
    expect(wheelZoomDelta({ deltaY: 5000, deltaMode: PIXEL, ctrlKey: false })).toBe(-2)
  })
})
