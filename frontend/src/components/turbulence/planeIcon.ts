import L from 'leaflet'
import { categoryColors, type TurbulenceCategory } from '@/api/turbulence'

const planePath =
  'M12 2c.6 0 1 .5 1 1.2V9l8 4.6v2l-8-2.4v5.3l2.3 1.7V22L12 21l-3.3 1v-1.8L11 18.5v-5.3L3 15.6v-2L11 9V3.2C11 2.5 11.4 2 12 2z'

/** A plane silhouette rotated to its track and filled with its turbulence category colour. */
export function planeIcon(category: TurbulenceCategory, trackDeg = 0, size = 22, highlighted = false): L.DivIcon {
  const fill = categoryColors[category]
  const stroke = highlighted ? '#0f172a' : '#ffffff'
  const html =
    `<svg width="${size}" height="${size}" viewBox="0 0 24 24" style="transform: rotate(${trackDeg}deg);` +
    ` filter: drop-shadow(0 1px 1px rgba(0,0,0,.45))"><path d="${planePath}" fill="${fill}"` +
    ` stroke="${stroke}" stroke-width="${highlighted ? 1.6 : 1}"/></svg>`
  return L.divIcon({ html, className: 'plane-icon', iconSize: [size, size], iconAnchor: [size / 2, size / 2] })
}
