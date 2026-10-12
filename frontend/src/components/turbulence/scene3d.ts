// Pieces of the live map's deck.gl 3D scene.
import { BitmapLayer } from '@deck.gl/layers'
import { TileLayer } from '@deck.gl/geo-layers'
import { baseMapUrl } from '@/components/turbulence/baseMap'
import {
  type TurbulenceCategory,
  type TurbulenceVolume,
  type Thresholds,
  classify
} from '@/api/turbulence'

export interface Voxel {
  lat: number
  lon: number
  levelFt: number
  bottomFt: number
  topFt: number
  edr: number
  category: TurbulenceCategory
  /** For a merged column: the highest level in it (levelFt is the lowest). */
  topLevelFt?: number
}

export const ftToM = 0.3048
export const blue: [number, number, number] = [16, 50, 79] // --my-blue

export function hexRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

export function toVoxels(v: TurbulenceVolume, thresholds: Thresholds): Voxel[] {
  const levels = v.levelsFt
  const out: Voxel[] = []
  for (let k = 0; k < v.voxels.length; k += 4) {
    const i = v.voxels[k + 2]!
    const level = levels[i]!
    const prev = levels[i - 1]
    const next = levels[i + 1]
    const edr = v.voxels[k + 3]! / 200
    out.push({
      lat: v.voxels[k]! / 100,
      lon: v.voxels[k + 1]! / 100,
      levelFt: level,
      bottomFt: prev === undefined ? Math.max(level - 1000, 0) : (prev + level) / 2,
      topFt: next === undefined ? level + 1000 : (next + level) / 2,
      edr,
      category: classify(edr, thresholds)
    })
  }
  return out
}

/**
 * Stacks vertically adjacent blocks of the same category at one grid point into one column, keeping the worst EDR.
 * Turbulence layers are deep, so this cuts the block count about 5x and makes showing every level affordable.
 */
export function mergeColumns(voxels: Voxel[]): Voxel[] {
  const byPoint = new Map<string, Voxel[]>()
  for (const v of voxels) {
    const key = `${v.lat},${v.lon}`
    const list = byPoint.get(key)
    if (list) list.push(v)
    else byPoint.set(key, [v])
  }
  const out: Voxel[] = []
  for (const list of byPoint.values()) {
    list.sort((a, b) => a.levelFt - b.levelFt)
    let run: Voxel | undefined
    for (const v of list) {
      if (run && run.category === v.category && run.topFt === v.bottomFt) {
        run.topFt = v.topFt
        run.topLevelFt = v.levelFt
        run.edr = Math.max(run.edr, v.edr)
      } else {
        run = { ...v, topLevelFt: v.levelFt }
        out.push(run)
      }
    }
  }
  return out
}

/** The columns inside a band of levels, cut to it. */
export function clipColumns(columns: Voxel[], lo: number, hi: number, halfGapFt = 500): Voxel[] {
  const out: Voxel[] = []
  for (const c of columns) {
    const top = c.topLevelFt ?? c.levelFt
    if (top < lo || c.levelFt > hi) continue
    if (c.levelFt >= lo && top <= hi) out.push(c)
    else
      out.push({
        ...c,
        levelFt: Math.max(c.levelFt, lo),
        topLevelFt: Math.min(top, hi),
        bottomFt: Math.max(c.bottomFt, lo - halfGapFt),
        topFt: Math.min(c.topFt, hi + halfGapFt)
      })
  }
  return out
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
