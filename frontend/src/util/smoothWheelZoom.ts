import type L from 'leaflet'

/**
 * Continuous mouse-wheel and trackpad zoom for a Leaflet map, in place of Leaflet's own, which jumps a whole level per
 * gesture and drops wheel events while its zoom animation runs. Every animation frame the zoom eases towards where the
 * wheel has asked for, keeping the point under the pointer still; a trackpad pinch (a ctrl+wheel in browsers) follows
 * the fingers one to one. Needs `scrollWheelZoom: false` and `zoomSnap: 0` on the map.
 */

/** Zoom levels per wheel pixel: a mouse notch (100 px) is one level. */
const wheelRate = 1 / 100
/** A trackpad pinch: browsers send ctrl+wheel with deltaY = -100 · ln(scale), so this is log2 of the pinch scale. */
const pinchRate = 1 / (100 * Math.LN2)
/** How fast the zoom catches up with the wheel, as a time constant in ms. */
const easeMs = 70

/** Zoom levels a wheel event asks for (positive zooms in), whatever the browser's delta unit. */
export function wheelZoomDelta(e: Pick<WheelEvent, 'deltaY' | 'deltaMode' | 'ctrlKey'>): number {
  // deltaMode 1 is lines, 2 pages, 0 pixels.
  const px = e.deltaMode === 1 ? e.deltaY * 40 : e.deltaMode === 2 ? e.deltaY * 800 : e.deltaY
  // A single event never asks for more than two levels (some mice send huge deltas).
  return Math.max(Math.min(-px * (e.ctrlKey ? pinchRate : wheelRate), 2), -2)
}

/** Attaches the handler; returns a function that detaches it. */
export function smoothWheelZoom(map: L.Map): () => void {
  // Leaflet's internals this drives, the same ones its own pinch zoom uses.
  const m = map as L.Map & {
    _move(center: L.LatLng, zoom: number, data?: object): void
    _moveStart(zoomChanged: boolean, noMoveStart: boolean): void
    _moveEnd(zoomChanged: boolean): void
    _stop(): void
    _animatingZoom?: boolean
  }
  const container = map.getContainer()
  let target = 0
  let zoom = 0
  let anchor: { latlng: L.LatLng; point: L.Point } | undefined
  let frame: number | undefined
  let last = 0
  let idle: number | undefined

  const centerFor = (z: number) => {
    const { latlng, point } = anchor!
    const offset = point.subtract(map.getSize().divideBy(2))
    return map.unproject(map.project(latlng, z).subtract(offset), z)
  }

  const step = (now: number) => {
    const dt = Math.min(now - last, 100)
    last = now
    const left = target - zoom
    zoom = Math.abs(left) < 0.001 ? target : zoom + left * (1 - Math.exp(-dt / easeMs))
    m._move(centerFor(zoom), zoom, { pinch: true, round: false })
    frame = zoom === target ? undefined : requestAnimationFrame(step)
    if (frame === undefined) idle = window.setTimeout(finish, 120)
  }

  // The gesture is over: a plain zoom event lets the layers drop the old tile level, then the usual moveend.
  const finish = () => {
    idle = undefined
    if (!anchor) return
    m._move(centerFor(zoom), zoom)
    map.fire('zoom')
    anchor = undefined
    m._moveEnd(true)
  }

  const onWheel = (e: WheelEvent) => {
    e.preventDefault()
    if (m._animatingZoom) return
    const point = map.mouseEventToContainerPoint(e)
    window.clearTimeout(idle)
    if (!anchor) {
      m._stop()
      zoom = target = map.getZoom()
      m._moveStart(true, false)
    }
    // The point under the pointer stays put, so moving the pointer mid-zoom re-anchors there.
    anchor = { latlng: map.containerPointToLatLng(point), point }
    target = Math.max(Math.min(target + wheelZoomDelta(e), map.getMaxZoom()), map.getMinZoom())
    if (e.ctrlKey) {
      // A pinch is already smooth (one event per frame): follow it exactly.
      zoom = target
      cancelAnimationFrame(frame ?? 0)
      frame = undefined
      m._move(centerFor(zoom), zoom, { pinch: true, round: false })
      idle = window.setTimeout(finish, 120)
    } else if (frame === undefined) {
      last = performance.now()
      frame = requestAnimationFrame(step)
    }
  }

  container.addEventListener('wheel', onWheel, { passive: false })
  return () => {
    container.removeEventListener('wheel', onWheel)
    cancelAnimationFrame(frame ?? 0)
    window.clearTimeout(idle)
  }
}
