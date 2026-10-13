// Pieces of the live map's deck.gl 3D scene.
import { BitmapLayer } from '@deck.gl/layers'
import { TileLayer } from '@deck.gl/geo-layers'
import { baseMapUrl } from '@/components/turbulence/baseMap'

export const ftToM = 0.3048
export const blue: [number, number, number] = [16, 50, 79] // --my-blue

export { hexRgb } from '@/components/turbulence/color'

/** The light-grey basemap as deck.gl bitmap tiles. */
export function basemapLayer(): TileLayer {
  return new TileLayer({
    id: 'basemap',
    // Its own URL (the parameter changes nothing in the tile): Safari reuses the 2D map's cached <img> tiles, fetched
    // without CORS, for deck.gl's CORS fetches of the same URL and rejects them, leaving 3D without a basemap.
    data: `${baseMapUrl}?blankTile=false`,
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
