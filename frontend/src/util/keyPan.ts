/**
 * W/A/S/D pan the map and Q/E rotate it while held, like a game: smooth and continuous, at a steady speed. Keys typed
 * into a text field (the Add flight box) or pressed with Ctrl/Cmd/Alt are left alone.
 */

/** Screen pixels per second the map moves while a key is held. */
export const keyPanSpeed = 700

/** Degrees per second the map turns while Q or E is held. */
export const keyRotateSpeed = 90

const turns: Record<string, number> = {
  // Q turns the map counter-clockwise, E clockwise.
  KeyQ: 1,
  KeyE: -1
}

const directions: Record<string, [number, number]> = {
  KeyW: [0, -1],
  KeyA: [-1, 0],
  KeyS: [0, 1],
  KeyD: [1, 0]
}

/** The unit direction the held keys pan the view, in screen axes (x right, y down); [0, 0] when they cancel out. */
export function keyPanDirection(held: Iterable<string>): [number, number] {
  let x = 0
  let y = 0
  for (const code of held) {
    const d = directions[code]
    if (d) {
      x += d[0]
      y += d[1]
    }
  }
  const length = Math.hypot(x, y)
  return length ? [x / length, y / length] : [0, 0]
}

/** Which way the held keys turn the map: 1 counter-clockwise (Q), -1 clockwise (E), 0 for neither or both. */
export function keyRotateDirection(held: Iterable<string>): number {
  let turn = 0
  for (const code of held) turn += turns[code] ?? 0
  return turn
}

/** Whether a key event belongs to something the user is typing into rather than the map. */
export function isTypingTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || typeof el.tagName !== 'string') return false
  return el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)
}

/**
 * Listens for W/A/S/D and Q/E on the window. Every animation frame it calls `pan(dx, dy)` with the screen pixels to
 * move and `rotate(degrees)` with how far to turn the map (positive is counter-clockwise), then `end()` once all keys
 * are up. Returns a function that stops listening.
 */
export function listenKeyPan(
  pan: (dx: number, dy: number) => void,
  end: () => void,
  rotate: (degrees: number) => void = () => undefined
): () => void {
  const held = new Set<string>()
  let frame: number | undefined
  let last = 0

  const step = (now: number) => {
    const dt = Math.min((now - last) / 1000, 0.05)
    last = now
    const [x, y] = keyPanDirection(held)
    if (x || y) pan(x * keyPanSpeed * dt, y * keyPanSpeed * dt)
    const turn = keyRotateDirection(held)
    if (turn) rotate(turn * keyRotateSpeed * dt)
    frame = held.size ? requestAnimationFrame(step) : undefined
    if (!frame) end()
  }
  const down = (e: KeyboardEvent) => {
    if (
      !(e.code in directions || e.code in turns) ||
      e.ctrlKey ||
      e.metaKey ||
      e.altKey ||
      isTypingTarget(e.target)
    )
      return
    e.preventDefault()
    held.add(e.code)
    if (frame === undefined) {
      last = performance.now()
      frame = requestAnimationFrame(step)
    }
  }
  const up = (e: KeyboardEvent) => held.delete(e.code)
  // Switching tabs or windows mid-press never delivers the keyup.
  const release = () => held.clear()

  window.addEventListener('keydown', down)
  window.addEventListener('keyup', up)
  window.addEventListener('blur', release)
  return () => {
    window.removeEventListener('keydown', down)
    window.removeEventListener('keyup', up)
    window.removeEventListener('blur', release)
    if (frame !== undefined) cancelAnimationFrame(frame)
  }
}
