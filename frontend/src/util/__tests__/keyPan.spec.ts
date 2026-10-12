import { describe, expect, it } from 'vitest'
import { isTypingTarget, keyPanDirection } from '../keyPan'

describe('keyPanDirection', () => {
  it('maps W/A/S/D to screen directions', () => {
    expect(keyPanDirection(['KeyW'])).toEqual([0, -1])
    expect(keyPanDirection(['KeyA'])).toEqual([-1, 0])
    expect(keyPanDirection(['KeyS'])).toEqual([0, 1])
    expect(keyPanDirection(['KeyD'])).toEqual([1, 0])
  })

  it('moves diagonally at the same speed as straight', () => {
    const [x, y] = keyPanDirection(['KeyW', 'KeyD'])
    expect(Math.hypot(x, y)).toBeCloseTo(1)
    expect(x).toBeGreaterThan(0)
    expect(y).toBeLessThan(0)
  })

  it('stands still when opposite keys cancel or no pan key is held', () => {
    expect(keyPanDirection(['KeyA', 'KeyD'])).toEqual([0, 0])
    expect(keyPanDirection(['KeyQ'])).toEqual([0, 0])
    expect(keyPanDirection([])).toEqual([0, 0])
  })
})

describe('isTypingTarget', () => {
  it('treats text fields as typing and anything else as the map', () => {
    expect(isTypingTarget({ tagName: 'INPUT' } as unknown as EventTarget)).toBe(true)
    expect(isTypingTarget({ tagName: 'TEXTAREA' } as unknown as EventTarget)).toBe(true)
    expect(
      isTypingTarget({ tagName: 'DIV', isContentEditable: true } as unknown as EventTarget)
    ).toBe(true)
    expect(isTypingTarget({ tagName: 'DIV' } as unknown as EventTarget)).toBe(false)
    expect(isTypingTarget(null)).toBe(false)
  })
})
