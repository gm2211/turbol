/**
 * The HRRR CONUS Lambert Conformal grid that GTGN and GTG use, ported from the backend's LambertGrid: 1799 x 1059
 * points at 3 km, tangent at 38.5N, central meridian 97.5W, spherical earth (R = 6371229 m).
 */

import { rint, toRadians } from './geo'

export const nx = 1799
export const ny = 1059
const dx = 3000.0
const earthRadius = 6371229.0
const standardParallel = toRadians(38.5)
const centralMeridian = toRadians(-97.5)
const firstLat = 21.138124
const firstLon = 237.28048 - 360.0

const n = Math.sin(standardParallel)
const f =
  (Math.cos(standardParallel) * Math.pow(Math.tan(Math.PI / 4 + standardParallel / 2), n)) / n
const rho0 = (earthRadius * f) / Math.pow(Math.tan(Math.PI / 4 + standardParallel / 2), n)

function project(lat: number, lon: number): [number, number] {
  const rho = (earthRadius * f) / Math.pow(Math.tan(Math.PI / 4 + toRadians(lat) / 2), n)
  let dLon = toRadians(lon) - centralMeridian
  if (dLon > Math.PI) dLon -= 2 * Math.PI
  if (dLon < -Math.PI) dLon += 2 * Math.PI
  const theta = n * dLon
  return [rho * Math.sin(theta), rho0 - rho * Math.cos(theta)]
}

const [x0, y0] = project(firstLat, firstLon)

/** Flat index of the nearest grid point, or -1 outside the grid (where GTG has no data). */
export function nearestIndex(lat: number, lon: number): number {
  if (lat < 0 || lat > 89) return -1
  const [x, y] = project(lat, lon)
  const i = rint((x - x0) / dx)
  const j = rint((y - y0) / dx)
  return i < 0 || i >= nx || j < 0 || j >= ny ? -1 : j * nx + i
}
