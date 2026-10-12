/**
 * Great-circle geometry, ported from the backend's RouteAnalyzer and PackGrid with the same formulas in the same
 * order, so the phone and the server agree on where a flight is (src/fly/__tests__/parity.spec.ts checks this).
 */

export const earthRadiusKm = 6371.0

// java.lang.Math's conversion constants, so angles come out bit-for-bit as on the server.
export const toRadians = (deg: number) => deg * 0.017453292519943295
const toDegrees = (rad: number) => rad * 57.29577951308232
const square = (x: number) => x * x

export function greatCircleKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const p1 = toRadians(lat1)
  const p2 = toRadians(lat2)
  const dLat = p2 - p1
  const dLon = toRadians(lon2 - lon1)
  const a = square(Math.sin(dLat / 2)) + Math.cos(p1) * Math.cos(p2) * square(Math.sin(dLon / 2))
  return 2 * earthRadiusKm * Math.asin(Math.min(1.0, Math.sqrt(a)))
}

/** Initial great-circle bearing from point 1 to point 2, in degrees clockwise from north. */
export function bearing(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const p1 = toRadians(lat1)
  const p2 = toRadians(lat2)
  const dl = toRadians(lon2 - lon1)
  const y = Math.sin(dl) * Math.cos(p2)
  const x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
  return (toDegrees(Math.atan2(y, x)) + 360) % 360
}

/** Point at fraction f along the great circle (spherical interpolation). */
export function intermediate(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number,
  f: number
): [number, number] {
  const p1 = toRadians(lat1)
  const l1 = toRadians(lon1)
  const p2 = toRadians(lat2)
  const l2 = toRadians(lon2)
  const d = greatCircleKm(lat1, lon1, lat2, lon2) / earthRadiusKm
  if (d < 1e-9) return [lat1, lon1]
  const a = Math.sin((1 - f) * d) / Math.sin(d)
  const b = Math.sin(f * d) / Math.sin(d)
  const x = a * Math.cos(p1) * Math.cos(l1) + b * Math.cos(p2) * Math.cos(l2)
  const y = a * Math.cos(p1) * Math.sin(l1) + b * Math.cos(p2) * Math.sin(l2)
  const z = a * Math.sin(p1) + b * Math.sin(p2)
  return [toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y))), toDegrees(Math.atan2(y, x))]
}

/** Destination point given start, bearing (deg) and distance. */
export function destination(
  lat: number,
  lon: number,
  bearingDeg: number,
  distanceKm: number
): [number, number] {
  const d = distanceKm / earthRadiusKm
  const p1 = toRadians(lat)
  const l1 = toRadians(lon)
  const b = toRadians(bearingDeg)
  const p2 = Math.asin(Math.sin(p1) * Math.cos(d) + Math.cos(p1) * Math.sin(d) * Math.cos(b))
  const l2 =
    l1 +
    Math.atan2(Math.sin(b) * Math.sin(d) * Math.cos(p1), Math.cos(d) - Math.sin(p1) * Math.sin(p2))
  return [toDegrees(p2), ((toDegrees(l2) + 540) % 360) - 180]
}

/** Math.rint then toInt: round half to even, as the backend rounds (Math.round rounds half up), and never -0. */
export function rint(x: number): number {
  const r = Math.round(x)
  const even = r - x === 0.5 && r % 2 !== 0 ? r - 1 : r
  return even === 0 ? 0 : even
}

type Vec = [number, number, number]

const unit = (lat: number, lon: number): Vec => {
  const p = toRadians(lat)
  const l = toRadians(lon)
  return [Math.cos(p) * Math.cos(l), Math.cos(p) * Math.sin(l), Math.sin(p)]
}
const cross = (u: Vec, v: Vec): Vec => [
  u[1] * v[2] - u[2] * v[1],
  u[2] * v[0] - u[0] * v[2],
  u[0] * v[1] - u[1] * v[0]
]
const dot = (u: Vec, v: Vec) => u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
const normalize = (v: Vec): Vec => {
  const len = Math.sqrt(dot(v, v))
  return [v[0] / len, v[1] / len, v[2] / len]
}

/**
 * The cells of a Flight Pack (the backend's PackGrid): rows about every 6 km along the great circle from origin to
 * destination, columns every 6 km across it. Cell (row, col) is `row * alongStepKm` along the route, then
 * `(col - halfCols) * crossStepKm` at right angles to it (positive is left of the direction of travel).
 */
export class PackGrid {
  readonly totalKm: number
  readonly rows: number
  readonly alongStepKm: number
  readonly halfCols: number
  readonly cols: number
  private readonly a: Vec
  private readonly n: Vec
  private readonly t: Vec

  constructor(
    readonly originLat: number,
    readonly originLon: number,
    readonly destLat: number,
    readonly destLon: number,
    targetAlongStepKm = 6.0,
    readonly crossStepKm = 6.0,
    halfWidthKm = 240.0
  ) {
    this.totalKm = greatCircleKm(originLat, originLon, destLat, destLon)
    this.rows = Math.ceil(this.totalKm / targetAlongStepKm) + 1
    this.alongStepKm = this.totalKm / (this.rows - 1)
    this.halfCols = rint(halfWidthKm / crossStepKm)
    this.cols = 2 * this.halfCols + 1
    this.a = unit(originLat, originLon)
    this.n = normalize(cross(this.a, unit(destLat, destLon)))
    this.t = cross(this.n, this.a)
  }

  pointAt(alongKm: number, crossKm: number): [number, number] {
    const s = alongKm / earthRadiusKm
    const c = crossKm / earthRadiusKm
    const [cs, ss, cc, sc] = [Math.cos(s), Math.sin(s), Math.cos(c), Math.sin(c)]
    const { a, t, n } = this
    const v: Vec = [
      (a[0] * cs + t[0] * ss) * cc + n[0] * sc,
      (a[1] * cs + t[1] * ss) * cc + n[1] * sc,
      (a[2] * cs + t[2] * ss) * cc + n[2] * sc
    ]
    return [
      toDegrees(Math.atan2(v[2], Math.sqrt(v[0] * v[0] + v[1] * v[1]))),
      toDegrees(Math.atan2(v[1], v[0]))
    ]
  }

  cellCenter(row: number, col: number): [number, number] {
    return this.pointAt(row * this.alongStepKm, (col - this.halfCols) * this.crossStepKm)
  }

  /** [along, cross] in km: the inverse of pointAt. */
  locate(lat: number, lon: number): [number, number] {
    const x = unit(lat, lon)
    return [
      earthRadiusKm * Math.atan2(dot(x, this.t), dot(x, this.a)),
      earthRadiusKm * Math.asin(Math.max(-1.0, Math.min(1.0, dot(x, this.n))))
    ]
  }

  /** The nearest cell, or undefined outside the pack. */
  cellOf(lat: number, lon: number): [number, number] | undefined {
    const [along, across] = this.locate(lat, lon)
    const row = rint(along / this.alongStepKm)
    const col = rint(across / this.crossStepKm) + this.halfCols
    return row >= 0 && row < this.rows && col >= 0 && col < this.cols ? [row, col] : undefined
  }
}
