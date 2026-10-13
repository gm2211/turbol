import { describe, expect, it } from 'vitest'
import { type ActivityEnv, createPageActivity } from '../usePageActive'

/** A browser with a clock the test moves and events it fires. */
function fakeEnv() {
  let time = 0
  const listeners = new Map<string, Set<() => void>>()
  const timers = new Map<number, { at: number; fn: () => void }>()
  let nextTimer = 1
  const doc = { visibilityState: 'visible' }
  const on = (type: string, fn: () => void) => {
    if (!listeners.has(type)) listeners.set(type, new Set())
    listeners.get(type)!.add(fn)
  }
  const off = (type: string, fn: () => void) => listeners.get(type)?.delete(fn)
  const env: ActivityEnv = {
    document: Object.assign(doc, { addEventListener: on, removeEventListener: off }),
    window: { addEventListener: on, removeEventListener: off },
    now: () => time,
    setTimeout: (fn, ms) => {
      const id = nextTimer++
      timers.set(id, { at: time + ms, fn })
      return id
    },
    clearTimeout: (h) => timers.delete(h as number)
  }
  return {
    env,
    fire: (type: string) => listeners.get(type)?.forEach((fn) => fn()),
    hide: (hidden: boolean) => {
      doc.visibilityState = hidden ? 'hidden' : 'visible'
      listeners.get('visibilitychange')?.forEach((fn) => fn())
    },
    advance(ms: number) {
      const end = time + ms
      for (;;) {
        const due = [...timers.entries()]
          .filter(([, t]) => t.at <= end)
          .sort((a, b) => a[1].at - b[1].at)[0]
        if (!due) break
        timers.delete(due[0])
        time = due[1].at
        due[1].fn()
      }
      time = end
    }
  }
}

const minute = 60_000

describe('createPageActivity', () => {
  it('is active while visible, and inactive while the tab is hidden', () => {
    const f = fakeEnv()
    const { active } = createPageActivity(f.env, 10 * minute)
    expect(active.value).toBe(true)
    f.hide(true)
    expect(active.value).toBe(false)
    f.hide(false)
    expect(active.value).toBe(true)
  })

  it('goes idle after 10 minutes without input, and wakes on the next input', () => {
    const f = fakeEnv()
    const { active } = createPageActivity(f.env, 10 * minute)
    f.advance(9 * minute)
    expect(active.value).toBe(true)
    f.advance(2 * minute)
    expect(active.value).toBe(false)
    f.fire('pointermove')
    expect(active.value).toBe(true)
    f.advance(9 * minute)
    expect(active.value).toBe(true)
    f.advance(2 * minute)
    expect(active.value).toBe(false)
  })

  it('input keeps pushing idleness back', () => {
    const f = fakeEnv()
    const { active } = createPageActivity(f.env, 10 * minute)
    for (let i = 0; i < 5; i++) {
      f.advance(6 * minute)
      f.fire('keydown')
    }
    expect(active.value).toBe(true)
    f.advance(11 * minute)
    expect(active.value).toBe(false)
  })

  it('returning to a tab that sat hidden for a long time is active, not idle', () => {
    const f = fakeEnv()
    const { active } = createPageActivity(f.env, 10 * minute)
    f.hide(true)
    f.advance(60 * minute)
    f.hide(false)
    expect(active.value).toBe(true)
  })
})
