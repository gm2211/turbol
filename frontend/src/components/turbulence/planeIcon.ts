import L from 'leaflet'
import { categoryColors, type TurbulenceCategory } from '@/api/turbulence'

const planePath =
  'M12 2c.6 0 1 .5 1 1.2V9l8 4.6v2l-8-2.4v5.3l2.3 1.7V22L12 21l-3.3 1v-1.8L11 18.5v-5.3L3 15.6v-2L11 9V3.2C11 2.5 11.4 2 12 2z'

/** A plane silhouette rotated to its track and filled with its turbulence category colour. */
export function planeIcon(
  category: TurbulenceCategory,
  trackDeg = 0,
  size = 22,
  highlighted = false
): L.DivIcon {
  const fill = categoryColors[category]
  const stroke = highlighted ? '#0f172a' : '#ffffff'
  const html =
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24" style="transform: rotate(${trackDeg}deg);` +
    ` filter: drop-shadow(0 1px 1px rgba(0,0,0,.45))"><path d="${planePath}" fill="${fill}"` +
    ` stroke="${stroke}" stroke-width="${highlighted ? 1.6 : 1}"/></svg>`
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
    options: { fillColor: string; trackDeg: number; size: number }
  }) {
    const ctx = this._renderer._ctx
    if (!ctx || this._empty()) return
    planeShape ??= new Path2D(planePath)
    const { fillColor, trackDeg, size } = this.options
    ctx.save()
    ctx.translate(this._point.x, this._point.y)
    ctx.rotate((trackDeg * Math.PI) / 180)
    ctx.scale(size / 24, size / 24)
    ctx.translate(-12, -12)
    ctx.fillStyle = fillColor
    ctx.fill(planeShape)
    ctx.lineWidth = 1
    ctx.strokeStyle = 'rgba(255,255,255,.9)'
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
    fillColor: categoryColors[category],
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
  const options = marker.options as L.CircleMarkerOptions & { trackDeg: number; size: number }
  options.fillColor = categoryColors[category]
  options.trackDeg = trackDeg
  options.size = size
  marker.setRadius(size / 2)
  marker.setLatLng(latlng)
}
