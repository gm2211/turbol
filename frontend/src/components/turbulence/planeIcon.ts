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

let planeShape: Path2D | undefined

/**
 * The same silhouette drawn on a Leaflet canvas renderer: thousands of these stay smooth where thousands of DOM
 * markers would not. Clicks hit the circle of radius size / 2.
 */
const CanvasPlaneClass = (
  L.CircleMarker as unknown as { extend(props: object): new (...args: unknown[]) => L.CircleMarker }
).extend({
  _updatePath(this: {
    _renderer: { _ctx?: CanvasRenderingContext2D }
    _point: L.Point
    _empty(): boolean
    options: { ring?: string; trackDeg: number; size: number }
  }) {
    const ctx = this._renderer._ctx
    if (!ctx || this._empty()) return
    planeShape ??= new Path2D(planePath)
    const { ring, trackDeg, size } = this.options
    ctx.save()
    ctx.translate(this._point.x, this._point.y)
    if (ring) {
      ctx.beginPath()
      ctx.arc(0, 0, size * 0.55, 0, 2 * Math.PI)
      ctx.globalAlpha = 0.22
      ctx.fillStyle = ring
      ctx.fill()
      ctx.globalAlpha = 1
      ctx.lineWidth = 2
      ctx.strokeStyle = ring
      ctx.stroke()
    }
    ctx.rotate((trackDeg * Math.PI) / 180)
    ctx.scale(size / 24, size / 24)
    ctx.translate(-12, -12)
    ctx.fillStyle = planeFill
    ctx.fill(planeShape)
    ctx.lineWidth = 1.2
    ctx.strokeStyle = 'rgba(255,255,255,.95)'
    ctx.stroke(planeShape)
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
