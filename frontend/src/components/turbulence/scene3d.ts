// Pieces of the live map's deck.gl 3D scene.
import { BitmapLayer } from '@deck.gl/layers'
import { TileLayer } from '@deck.gl/geo-layers'
import { baseMapUrl } from '@/components/turbulence/baseMap'

export const ftToM = 0.3048
export const blue: [number, number, number] = [16, 50, 79] // --my-blue

export function hexRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/** The light-grey basemap as deck.gl bitmap tiles. */
export function basemapLayer(): TileLayer {
  return new TileLayer({
    id: 'basemap',
    data: baseMapUrl,
    minZoom: 0,
    maxZoom: 12,
    tileSize: 256,
    renderSubLayers: (props) => {
      const [[west, south], [east, north]] = props.tile.boundingBox
      return new BitmapLayer(props, {
        data: undefined,
        image: props.data,
        bounds: [west, south, east, north]
      })
    }
  })
}

/** Tooltip box styled like --glass-panel-bg, more opaque to read over the scene. */
export const tooltipStyle = {
  background: 'rgba(24, 39, 52, 0.92)',
  color: 'rgba(255, 255, 255, 0.95)',
  borderRadius: '8px',
  padding: '6px 10px',
  fontSize: '12px',
  boxShadow: '0px 1px 3px #000000'
}
