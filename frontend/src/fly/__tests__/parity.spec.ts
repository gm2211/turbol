/**
 * The phone's port of the sampler and route analyzer must give the backend's answers. The fixture is written by the
 * backend's FlightPackParityTest from the same inputs; regenerate it there after a deliberate backend change.
 */
import { describe, expect, it } from 'vitest'
import type { RouteAnalysis } from '@/api/flights'
import type { AircraftClass } from '@/api/turbulence'
import { analyzeFlight, analyzeRemaining, upcoming } from '../analyzer'
import { PackGrid } from '../geo'
import { nearestIndex } from '../lambert'
import {
  type PackData,
  type PackFrame,
  type PackManifest,
  gridOf,
  packFrame,
  packSource,
  sample
} from '../pack'
import fixture from './fixtures/pack-parity.json'

/** An analysis as the backend's fixture writes it (FlightPackParityTest.analysisJson). */
interface Expected {
  totalDistanceKm: number
  durationMinutes: number
  start: number
  cruiseAltitudeFt: number
  aircraftClass: string
  /** lat, lon, km, altitudeFt, minutes, time, edr, category, source, nearGround */
  points: (string | number | boolean | null)[][]
  /** category, startMinute, endMinute */
  segments: (string | number)[][]
  summary: {
    verdict: string
    worstCategory: string
    maxEdr: number | null
    worstMinute: number | null
    worstLat: number | null
    worstLon: number | null
    minutesByCategory: Record<string, number>
    percentByCategory: Record<string, number>
    coveragePercent: number
    notes: string[]
  }
  dataSources: Record<
    'nowcastValid' | 'forecastIssued' | 'forecastUntil' | 'globalIssued',
    number | null
  >
}

const bytesOf = (base64: string) => Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
const close = (actual: number | undefined, expected: number | null, digits = 9) => {
  if (expected === null) expect(actual).toBeUndefined()
  else expect(actual).toBeCloseTo(expected, digits)
}

describe('pack geometry', () => {
  const g = fixture.geometry
  const grid = new PackGrid(g.origin[0], g.origin[1], g.destination[0], g.destination[1])

  it('lays out the same cells as the backend', () => {
    expect([grid.rows, grid.cols]).toEqual([g.rows, g.cols])
    expect(grid.alongStepKm).toBeCloseTo(g.alongStepKm, 9)
    expect(grid.crossStepKm).toBe(g.crossStepKm)
    expect(grid.totalKm).toBeCloseTo(g.totalKm, 9)
    for (const c of g.centers) {
      const [lat, lon] = grid.cellCenter(c.row, c.col)
      expect(lat).toBeCloseTo(c.lat, 9)
      expect(lon).toBeCloseTo(c.lon, 9)
    }
  })

  it('finds the same cell for any location, and nothing outside the corridor', () => {
    for (const p of g.points) {
      const [along, cross] = grid.locate(p.lat, p.lon)
      expect(along).toBeCloseTo(p.along, 6)
      expect(cross).toBeCloseTo(p.cross, 6)
      expect(grid.cellOf(p.lat, p.lon) ?? null).toEqual(p.cell)
    }
  })

  it('finds the same HRRR grid point, and none outside the grid', () => {
    for (const q of fixture.lambert) expect(nearestIndex(q.lat, q.lon)).toBe(q.index)
  })
})

describe('pack sampler', () => {
  it('reads back the EDR the backend sampled: nowcast, forecast and the global layer, blended the same way', () => {
    const sources = new Set<string>()
    for (const s of fixture.sampler) {
      const manifest = s.manifest as unknown as PackManifest
      const slabs = s.slabs as unknown as Record<string, string>
      const grid = gridOf(manifest)
      const frames = manifest.frames.map((f) => packFrame(f, bytesOf(slabs[f.url]), manifest.cols))
      const byTime = (kind: PackFrame['kind']) =>
        frames.filter((f) => f.kind === kind).sort((a, b) => a.validTime - b.validTime)
      const data: PackData = {
        grid,
        levelsFt: manifest.levelsFt,
        nowcast: frames.find((f) => f.kind === 'nowcast'),
        forecast: byTime('forecast'),
        global: byTime('global')
      }
      for (const q of s.queries) {
        expect(grid.cellOf(q.lat, q.lon)).toEqual([q.row, q.col])
        const r = sample(data, q.lat, q.lon, q.altitudeFt, q.time)
        sources.add(r.source)
        expect(r.source).toBe(q.source)
        if (q.edr === null) expect(r.edr).toBeNaN()
        else expect(r.edr).toBeCloseTo(q.edr, 12)
      }
    }
    // The queries exercise every branch, including GTG fading into the global layer at its edge.
    expect([...sources].sort()).toEqual(['Blend', 'Forecast', 'Global', 'NoData', 'Nowcast'])
  })
})

describe('route analyzer', () => {
  const a = fixture.analyzer
  type Point = number[]
  // The backend's frames are the same everywhere, varying only by level and hour, so every cell of the flight's pack
  // gets the level's code. Outside GTG's grid the phone ignores GTG cells, as the server has no GTG there.
  const packFor = (origin: Point, destination: Point, withGlobal: boolean): PackData => {
    const grid = new PackGrid(origin[0], origin[1], destination[0], destination[1])
    const uniform = (
      kind: PackFrame['kind'],
      validTime: number,
      issued: number,
      codes: number[]
    ): PackFrame => {
      const perLevel = grid.rows * grid.cols
      const bytes = new Uint8Array(codes.length * perLevel)
      codes.forEach((code, li) => bytes.fill(code, li * perLevel, (li + 1) * perLevel))
      return {
        id: `${kind}${validTime}`,
        kind,
        validTime,
        issued,
        rowStart: 0,
        rowEnd: grid.rows,
        codes: bytes
      }
    }
    return {
      grid,
      levelsFt: a.levelsFt,
      nowcast: uniform('nowcast', a.nowcast.validTime, a.nowcast.validTime, a.nowcast.codes),
      forecast: a.forecast.map((f) => uniform('forecast', f.validTime, f.issued, f.codes)),
      global: withGlobal
        ? a.global.map((f) => uniform('global', f.validTime, f.issued, f.codes))
        : []
    }
  }

  function expectSame(actual: RouteAnalysis, expected: Expected) {
    expect(actual.totalDistanceKm).toBeCloseTo(expected.totalDistanceKm, 9)
    expect(actual.durationMinutes).toBeCloseTo(expected.durationMinutes, 9)
    expect(Date.parse(actual.start)).toBe(expected.start)
    expect(actual.cruiseAltitudeFt).toBe(expected.cruiseAltitudeFt)
    expect(actual.aircraftClass).toBe(expected.aircraftClass)
    expect(actual.points).toHaveLength(expected.points.length)
    actual.points.forEach((p, k) => {
      const [lat, lon, km, altitudeFt, minutes, time, edr, category, src, nearGround] =
        expected.points[k]
      expect(p.lat).toBeCloseTo(lat as number, 9)
      expect(p.lon).toBeCloseTo(lon as number, 9)
      expect(p.distanceKm).toBeCloseTo(km as number, 9)
      expect(p.altitudeFt).toBe(altitudeFt)
      expect(p.minutesFromStart).toBeCloseTo(minutes as number, 9)
      expect(Date.parse(p.time)).toBe(time)
      close(p.edr, edr as number | null, 12)
      expect([p.category, p.source, p.nearGround]).toEqual([category, src, nearGround])
    })
    expect(actual.segments.map((s) => s.category)).toEqual(expected.segments.map((s) => s[0]))
    actual.segments.forEach((s, k) => {
      expect(s.startMinute).toBeCloseTo(expected.segments[k][1] as number, 9)
      expect(s.endMinute).toBeCloseTo(expected.segments[k][2] as number, 9)
    })
    const sum = expected.summary
    expect(actual.summary.verdict).toBe(sum.verdict)
    expect(actual.summary.worstCategory).toBe(sum.worstCategory)
    close(actual.summary.maxEdr, sum.maxEdr, 12)
    close(actual.summary.worstMinute, sum.worstMinute)
    close(actual.summary.worstLat, sum.worstLat)
    close(actual.summary.worstLon, sum.worstLon)
    expect(actual.summary.minutesByCategory).toEqual(sum.minutesByCategory)
    expect(actual.summary.percentByCategory).toEqual(sum.percentByCategory)
    expect(actual.summary.coveragePercent).toBe(sum.coveragePercent)
    expect(actual.summary.notes).toEqual(sum.notes)
    const ds = actual.dataSources
    expect(
      [ds.nowcastValid, ds.forecastIssued, ds.forecastUntil, ds.globalIssued].map((t) =>
        t ? Date.parse(t) : null
      )
    ).toEqual([
      expected.dataSources.nowcastValid,
      expected.dataSources.forecastIssued,
      expected.dataSources.forecastUntil,
      expected.dataSources.globalIssued
    ])
  }

  it('analyzes whole flights, gate to gate, exactly as the backend, in and out of GTG', () => {
    for (const { input, expected } of a.flights) {
      const source = packSource(packFor(input.origin, input.destination, input.global))
      const analysis = analyzeFlight(
        source,
        input.origin[0],
        input.origin[1],
        input.destination[0],
        input.destination[1],
        input.departure,
        input.cruiseFt ?? undefined,
        input.aircraftClass as AircraftClass,
        { now: input.departure }
      )
      expectSame(analysis, expected as Expected)
    }
  })

  it('analyzes the rest of a flight in the air, and says what the next hour holds', () => {
    for (const { input, expected, edrNow, upcoming: next } of a.remaining) {
      const data = packFor(input.origin, input.destination, input.global)
      const ahead = analyzeRemaining(
        packSource(data),
        input.lat,
        input.lon,
        input.altitudeFt,
        input.groundSpeedKts,
        input.trackDeg,
        [input.destination[0], input.destination[1]],
        input.now,
        input.aircraftClass as AircraftClass,
        { now: input.now }
      )
      expectSame(ahead, expected as Expected)
      const now = sample(data, input.lat, input.lon, input.altitudeFt, input.now)
      if (edrNow === null) expect(now.edr).toBeNaN()
      else expect(now.edr).toBeCloseTo(edrNow, 12)
      const u = upcoming(ahead, now.edr, 'Medium')
      expect(u.headline).toBe(next.headline)
      expect([u.nowCategory, u.nextBumpCategory ?? null, u.worstNextHour]).toEqual([
        next.nowCategory,
        next.nextBumpCategory,
        next.worstNextHour
      ])
      close(u.nextBumpInMinutes, next.nextBumpInMinutes)
      close(u.nextBumpForMinutes, next.nextBumpForMinutes)
    }
  })
})
