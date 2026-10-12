import L from 'leaflet'
import { categoryColors, type TurbulenceCategory } from '@/api/turbulence'

export const planePath =
  'M12 2c.6 0 1 .5 1 1.2V9l8 4.6v2l-8-2.4v5.3l2.3 1.7V22L12 21l-3.3 1v-1.8L11 18.5v-5.3L3 15.6v-2L11 9V3.2C11 2.5 11.4 2 12 2z'

/**
 * Planes are one neutral colour; the turbulence at the plane's own altitude shows as a coloured ring, only when it's
 * Light or worse. (Colouring the plane itself clashed with the overlay, which is drawn for the map's altitude.)
 */
export const planeFill = '#334155'
export const planeHighlightFill = '#0f172a'

/** Ring colour for the turbulence a plane is in, or undefined when it's smooth or unknown. */
export function ringColor(category: TurbulenceCategory): string | undefined {
  return category === 'Smooth' || category === 'NoData' ? undefined : categoryColors[category]
}

/** A plane silhouette rotated to its track, with a turbulence ring when it's in Light or worse. */
export function planeIcon(
  category: TurbulenceCategory,
  trackDeg = 0,
  size = 22,
  highlighted = false
): L.DivIcon {
  const ring = ringColor(category)
  const html =
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24" style="overflow: visible;` +
    ` filter: drop-shadow(0 1px 1px rgba(0,0,0,.45))">` +
    (ring
      ? `<circle cx="12" cy="12" r="11" fill="${ring}" fill-opacity=".22" stroke="${ring}" stroke-width="2"/>`
      : '') +
    `<g transform="rotate(${trackDeg} 12 12) translate(2.4 2.4) scale(.8)"><path d="${planePath}"` +
    ` fill="${highlighted ? planeHighlightFill : planeFill}" stroke="#ffffff" stroke-width="${highlighted ? 1.8 : 1.2}"/>` +
    `</g></svg>`
  return L.divIcon({
    html,
    className: 'plane-icon',
    iconSize: [size, size],
    iconAnchor: [size / 2, size / 2]
  })
}

/**
 * Each plane and ring is drawn once into a small offscreen canvas per size and colour, then stamped (rotated) onto the
 * map's canvas: copying a bitmap is several times cheaper than filling and stroking the silhouette's path, which made
 * redrawing ~2,000 planes at the end of every pan take ~50 ms on a phone.
 */
const sprites = new Map<string, HTMLCanvasElement>()
/** Sprites are drawn at this many pixels per CSS pixel, so they stay crisp on retina screens and when rotated. */
const spriteScale = 3

function sprite(key: string, size: number, draw: (ctx: CanvasRenderingContext2D) => void) {
  let canvas = sprites.get(key)
  if (!canvas) {
    canvas = document.createElement('canvas')
    canvas.width = canvas.height = Math.ceil(size * spriteScale)
    const ctx = canvas.getContext('2d')!
    ctx.scale(spriteScale, spriteScale)
    draw(ctx)
    sprites.set(key, canvas)
  }
  return canvas
}

/** The silhouette, nose up, in a box `size` + 2 px wide (room for the outline). */
function planeSprite(size: number) {
  const box = size + 2
  return sprite(`plane:${size}`, box, (ctx) => {
    ctx.translate(1, 1)
    ctx.scale(size / 24, size / 24)
    const shape = new Path2D(planePath)
    ctx.fillStyle = planeFill
    ctx.fill(shape)
    ctx.lineWidth = 1.2
    ctx.strokeStyle = 'rgba(255,255,255,.95)'
    ctx.stroke(shape)
  })
}

/** The turbulence ring, radius 0.55 · size, in a box with room for its 2 px stroke. */
function ringSprite(size: number, color: string) {
  const r = size * 0.55
  const box = 2 * r + 4
  return sprite(`ring:${size}:${color}`, box, (ctx) => {
    ctx.beginPath()
    ctx.arc(box / 2, box / 2, r, 0, 2 * Math.PI)
    ctx.globalAlpha = 0.22
    ctx.fillStyle = color
    ctx.fill()
    ctx.globalAlpha = 1
    ctx.lineWidth = 2
    ctx.strokeStyle = color
    ctx.stroke()
  })
}

/**
 * The same silhouette drawn on a Leaflet canvas renderer: thousands of these stay smooth where thousands of DOM
 * markers would not. Clicks hit the circle of radius size / 2.
 */
const CanvasPlaneClass = (
  L.CircleMarker as unknown as { extend(props: object): new (...args: unknown[]) => L.CircleMarker }
).extend({
  _updatePath(this: {
    _renderer: { _ctx?: CanvasRenderingContext2D; _drawing?: boolean }
    _point: L.Point
    _empty(): boolean
    options: { ring?: string; trackDeg: number; size: number }
  }) {
    const ctx = this._renderer._ctx
    // Only inside the renderer's redraw, like Leaflet's own circles: Leaflet also calls this whenever a plane moves
    // and when the map stops, which drew every plane twice per redraw and planes outside the cleared area.
    if (!ctx || !this._renderer._drawing || this._empty()) return
    const { ring, trackDeg, size } = this.options
    const { x, y } = this._point
    if (ring) {
      const box = 2 * size * 0.55 + 4
      ctx.drawImage(ringSprite(size, ring), x - box / 2, y - box / 2, box, box)
    }
    const box = size + 2
    ctx.save()
    ctx.translate(x, y)
    ctx.rotate((trackDeg * Math.PI) / 180)
    ctx.drawImage(planeSprite(size), -box / 2, -box / 2, box, box)
    ctx.restore()
  }
})

export function canvasPlane(
  latlng: L.LatLngExpression,
  category: TurbulenceCategory,
  trackDeg: number,
  size: number,
  renderer: L.Canvas
): L.CircleMarker {
  return new CanvasPlaneClass(latlng, {
    renderer,
    radius: size / 2,
    ring: ringColor(category),
    trackDeg,
    size
  })
}

/** Moves and recolours a plane from `canvasPlane` in place (one redraw), instead of replacing it. */
export function updateCanvasPlane(
  marker: L.CircleMarker,
  latlng: L.LatLngExpression,
  category: TurbulenceCategory,
  trackDeg: number,
  size: number
) {
  const options = marker.options as L.CircleMarkerOptions & {
    ring?: string
    trackDeg: number
    size: number
  }
  options.ring = ringColor(category)
  options.trackDeg = trackDeg
  options.size = size
  marker.setRadius(size / 2)
  marker.setLatLng(latlng)
}
