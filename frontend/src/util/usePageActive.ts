import { ref, type Ref } from 'vue'

/** How long without a pointer, key, touch or wheel event before an open, visible page counts as idle. */
export const idleAfterMs = 10 * 60 * 1000

const inputEvents = ['pointerdown', 'pointermove', 'keydown', 'touchstart', 'wheel']

/** What [[createPageActivity]] needs from the browser, so it can run against a fake one. */
export interface ActivityEnv {
  document: {
    visibilityState: string
    addEventListener(type: string, listener: () => void, options?: unknown): void
    removeEventListener(type: string, listener: () => void): void
  }
  window: {
    addEventListener(type: string, listener: () => void, options?: unknown): void
    removeEventListener(type: string, listener: () => void): void
  }
  now(): number
  setTimeout(fn: () => void, ms: number): unknown
  clearTimeout(handle: unknown): void
}

/**
 * Whether anyone is looking: the tab is visible and there was input in the last `idleMs`. Polling pauses while it's
 * false, so a forgotten tab costs the server nothing.
 */
export function createPageActivity(env: ActivityEnv, idleMs = idleAfterMs) {
  const active = ref(false)
  let visible = env.document.visibilityState !== 'hidden'
  let idle = false
  let lastInput = env.now()
  let timer: unknown
  const update = () => (active.value = visible && !idle)

  // Wakes when the page would be idle if there had been no input since, and rechecks.
  const arm = (ms: number) => {
    env.clearTimeout(timer)
    timer = env.setTimeout(() => {
      const left = idleMs - (env.now() - lastInput)
      if (left > 0) return arm(left)
      timer = undefined
      idle = true
      update()
    }, ms)
  }
  const onInput = () => {
    lastInput = env.now()
    if (idle) {
      idle = false
      arm(idleMs)
      update()
    }
  }
  const onVisibility = () => {
    visible = env.document.visibilityState !== 'hidden'
    // Coming back counts as input: a tab shown after a long time away isn't idle.
    if (visible) onInput()
    update()
  }

  env.document.addEventListener('visibilitychange', onVisibility)
  for (const type of inputEvents) env.window.addEventListener(type, onInput, { passive: true })
  arm(idleMs)
  update()

  return {
    active: active as Ref<boolean>,
    dispose() {
      env.clearTimeout(timer)
      env.document.removeEventListener('visibilitychange', onVisibility)
      for (const type of inputEvents) env.window.removeEventListener(type, onInput)
    }
  }
}

let shared: ReturnType<typeof createPageActivity> | undefined

/** True while the tab is visible and has had input in the last 10 minutes; one shared flag for the whole page. */
export function usePageActive(): Ref<boolean> {
  shared ??= createPageActivity({
    document,
    window,
    now: () => Date.now(),
    setTimeout: (fn, ms) => window.setTimeout(fn, ms),
    clearTimeout: (handle) => window.clearTimeout(handle as number)
  })
  return shared.active
}
