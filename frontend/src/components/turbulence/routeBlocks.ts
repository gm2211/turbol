import type { ColumnBatch } from '@/components/turbulence/volumeTypes'
import { greatCircleKm } from '@/fly/geo'

/**
 * Finds the drawn turbulence block at a point, so the 3D route can be colored by the blocks it actually flies through.
 * The route's own forecast is sampled when the flight passes, as felt by its aircraft, and at the forecast grid's
 * full resolution; the blocks are one forecast hour, for a medium aircraft, coarsened into blocks. Colored by its own
 * forecast, about a third of JFK to MXP disagreed with the blocks around it.
 */
export class BlockIndex {
  /** Column indexes by 1-degree cell of their centre. */
  private readonly cells = new Map<number, number[]>()
  /** Widest block's half-width in degrees of longitude at the highest latitude, to bound the cells searched. */
  private readonly reachLat: number
  private readonly reachLon: number

  constructor(
    private readonly store: ColumnBatch,
    length: number
  ) {
    let maxKm = 0
    let maxLat = 0
    for (let i = 0; i < length; i++) {
      const key = cellKey(Math.floor(store.lat[i]!), Math.floor(store.lon[i]!))
      const list = this.cells.get(key)
      if (list) list.push(i)
      else this.cells.set(key, [i])
      maxKm = Math.max(maxKm, store.sizeKm[i]!)
      maxLat = Math.max(maxLat, Math.abs(store.lat[i]!))
    }
    const halfLat = halfWidthKm(maxKm) / kmPerDegree
    this.reachLat = Math.ceil(halfLat)
    this.reachLon = Math.ceil(halfLat / Math.max(Math.cos((maxLat * Math.PI) / 180), 0.1))
  }

  /**
   * Index into `categories` of the worst drawn block holding `lat`, `lon` (degrees) at `altitudeFt`, or -1 when no
   * block holds it (the air there is smooth, as only Light or worse is drawn).
   */
  categoryAt(lat: number, lon: number, altitudeFt: number): number {
    const s = this.store
    const la = Math.floor(lat)
    const lo = Math.floor(lon)
    const cosLat = Math.max(Math.cos((lat * Math.PI) / 180), 0.01)
    let worst = -1
    for (let dLat = -this.reachLat; dLat <= this.reachLat; dLat++)
      for (let dLon = -this.reachLon; dLon <= this.reachLon; dLon++) {
        const list = this.cells.get(cellKey(la + dLat, lo + dLon))
        if (!list) continue
        for (const i of list) {
          if (altitudeFt < s.bottomFt[i]! || altitudeFt >= s.topFt[i]!) continue
          const half = halfWidthKm(s.sizeKm[i]!) / kmPerDegree
          if (Math.abs(s.lat[i]! - lat) > half || Math.abs(s.lon[i]! - lon) * cosLat > half)
            continue
          worst = Math.max(worst, s.category[i]!)
        }
      }
    return worst
  }
}

const kmPerDegree = 111.32
const cellKey = (lat: number, lon: number) => (lat + 90) * 400 + (lon + 200)
/**
 * Half a block's width. Blocks are drawn 8% narrower than they are (see Map3D's getScale) to leave a seam between
 * them; a route crossing a seam belongs to the blocks on either side, not to smooth air.
 */
const halfWidthKm = (sizeKm: number) => sizeKm / 2

/** A stretch of the route between two points at most `maxKm` apart, and the route point it starts from. */
export interface RouteStep {
  from: [number, number, number]
  to: [number, number, number]
  /** Index of the route point this step belongs to (the last one before it). */
  point: number
}

/**
 * The route cut into steps of at most `maxKm`, each [lon, lat, altitude ft] at both ends, so each can take the color
 * of the block it's in: a line colored per route point (every ~15 km) runs half a step into the next block.
 */
export function routeSteps(
  points: { lat: number; lon: number; altitudeFt: number }[],
  maxKm: number
): RouteStep[] {
  const steps: RouteStep[] = []
  for (let k = 0; k + 1 < points.length; k++) {
    const a = points[k]!
    const b = points[k + 1]!
    const n = Math.max(1, Math.ceil(greatCircleKm(a.lat, a.lon, b.lat, b.lon) / maxKm))
    const at = (f: number): [number, number, number] => [
      a.lon + (b.lon - a.lon) * f,
      a.lat + (b.lat - a.lat) * f,
      a.altitudeFt + (b.altitudeFt - a.altitudeFt) * f
    ]
    for (let j = 0; j < n; j++) steps.push({ from: at(j / n), to: at((j + 1) / n), point: k })
  }
  return steps
}
