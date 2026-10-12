import { describe, expect, it } from 'vitest'
import type { SavedFlight } from '../flights'
import { greatCircleKm } from '../geo'
import { cruiseSpeedKmh } from '../analyzer'
import {
  estimate,
  flightProfile,
  kmAtMinutes,
  maxAccuracyM,
  taxiOutMinutes,
  withFix
} from '../position'

const jfk = { code: 'JFK', name: 'New York JFK', lat: 40.6413, lon: -73.7781 }
const lax = { code: 'LAX', name: 'Los Angeles', lat: 33.9416, lon: -118.4085 }
const departure = Date.parse('2026-01-15T14:00:00Z')
const minute = 60_000

const flight: SavedFlight = {
  id: 'JFK-LAX',
  label: 'UA 123',
  origin: jfk,
  destination: lax,
  departure,
  aircraftClass: 'Medium',
  cruiseFt: 35000,
  packDeparture: departure,
  savedAt: departure - 3600_000
}
const scheduledTakeoff = departure + taxiOutMinutes * minute

describe('position from the schedule', () => {
  it('is at the gate until the scheduled takeoff, then flies the planned profile', () => {
    const before = estimate(flight, scheduledTakeoff - 10 * minute)
    expect(before.phase).toBe('gate')
    expect(before.takeoff).toBe(scheduledTakeoff)
    expect([before.lat, before.lon]).toEqual([jfk.lat, jfk.lon])

    const hour = estimate(flight, scheduledTakeoff + 60 * minute)
    expect(hour.phase).toBe('air')
    expect(hour.basis).toBe('schedule')
    expect(hour.minutesIntoFlight).toBeCloseTo(60, 9)
    const profile = flightProfile(flight)
    expect(profile.minutesAt(greatCircleKm(jfk.lat, jfk.lon, hour.lat, hour.lon))).toBeCloseTo(
      60,
      3
    )
    expect(hour.altitudeFt).toBe(35000)
    // No fix since takeoff: 13.4 min p90 at 90 min, 6.6 at 30, so 10 at 60; plus 10 for not knowing the takeoff.
    expect(hour.uncertaintyMinutes).toBeCloseTo(20, 9)

    expect(hour.landing - hour.takeoff).toBeCloseTo(profile.minutesAt(profile.totalKm) * minute, 3)
    expect(hour.minutesToGo).toBeCloseTo(profile.minutesAt(profile.totalKm) - 60, 6)
    const after = estimate(flight, hour.landing + minute)
    expect(after.phase).toBe('landed')
    expect(after.lat).toBeCloseTo(lax.lat, 9)
    expect(after.lon).toBeCloseTo(lax.lon, 9)
  })

  it('counts from the takeoff the passenger tapped, without the schedule slack', () => {
    const tapped = { ...flight, takeoff: { time: departure + 40 * minute, source: 'tap' as const } }
    const e = estimate(tapped, departure + 70 * minute)
    expect([e.basis, e.phase]).toEqual(['tap', 'air'])
    expect(e.minutesIntoFlight).toBeCloseTo(30, 9)
    expect(e.uncertaintyMinutes).toBeCloseTo(6.6, 9)
  })

  it('inverts the profile: the distance flown after so many minutes', () => {
    const profile = flightProfile(flight)
    for (const m of [0.5, 5, 17, 60, 200, 300])
      expect(profile.minutesAt(kmAtMinutes(profile, m))).toBeCloseTo(m, 6)
    expect(kmAtMinutes(profile, -5)).toBe(0)
    expect(kmAtMinutes(profile, 10_000)).toBe(profile.totalKm)
  })
})

describe('position from GPS', () => {
  const overOhio = { time: departure + 90 * minute, lat: 40.2, lon: -84.0, speedMps: 230 }
  const onTheRunway = { time: departure + 25 * minute, lat: 40.64, lon: -73.79, speedMps: 70 }

  it('keeps fixes at flying speed near the route, and times the takeoff from one near the origin', () => {
    expect(withFix(flight, { ...overOhio, speedMps: 8 })).toBeUndefined()
    // Faster than any airliner, or vague enough to be Wi-Fi positioning: a bad fix.
    expect(withFix(flight, { ...overOhio, speedMps: 900 })).toBeUndefined()
    expect(withFix(flight, { ...overOhio, accuracyM: maxAccuracyM + 1 })).toBeUndefined()
    // A fix nowhere near the route is someone else's flight, or a bad reading.
    expect(withFix(flight, { ...overOhio, lat: 25.0, lon: -80.3 })).toBeUndefined()

    // GPS switched on mid-flight: the fix is kept, but it says nothing about when the plane took off.
    const midFlight = withFix(flight, overOhio)!
    expect(midFlight.airborneFix).toEqual(overOhio)
    expect(midFlight.takeoff).toBeUndefined()
    expect(withFix(midFlight, { ...overOhio, time: overOhio.time - minute })).toBeUndefined()

    // On the takeoff run it does, and better than a tap.
    const tapped = { ...flight, takeoff: { time: departure + 20 * minute, source: 'tap' as const } }
    const runway = withFix(tapped, onTheRunway)!
    expect(runway.takeoff?.source).toBe('gps')
    const fromOrigin = greatCircleKm(jfk.lat, jfk.lon, onTheRunway.lat, onTheRunway.lon)
    expect(runway.takeoff!.time).toBeCloseTo(
      onTheRunway.time - flightProfile(flight).minutesAt(fromOrigin) * minute,
      3
    )
    expect(onTheRunway.time - runway.takeoff!.time).toBeLessThan(0.2 * minute)
  })

  it('carries a cruise fix on at its ground speed, nearly exact while the fix is fresh', () => {
    const f = withFix(flight, overOhio)!
    const e = estimate(f, overOhio.time + 10 * minute)
    expect(e.basis).toBe('gps')
    expect(e.fixAgeMinutes).toBeCloseTo(10, 9)
    expect(e.uncertaintyMinutes).toBeCloseTo(0.48, 9)
    expect(e.groundSpeedKts).toBeCloseTo((230 * 3600) / 1852, 9)
    expect(greatCircleKm(overOhio.lat, overOhio.lon, e.lat, e.lon)).toBeCloseTo(138, 6)
    // "Minutes into the flight" count from the takeoff that puts the plane over Ohio at the fix's time.
    const flown =
      greatCircleKm(jfk.lat, jfk.lon, lax.lat, lax.lon) -
      greatCircleKm(overOhio.lat, overOhio.lon, lax.lat, lax.lon)
    const profile = flightProfile(f)
    expect(e.minutesIntoFlight).toBeCloseTo(profile.minutesAt(flown) + 10, 6)
    expect(e.planStart).toBeCloseTo(overOhio.time - profile.minutesAt(flown) * minute, 3)
  })

  it('lets a tailwind shorten the rest of the flight, descent included', () => {
    const fast = { ...overOhio, speedMps: 280 }
    const f = withFix(flight, fast)!
    const profile = flightProfile(f)
    const flown = profile.totalKm - greatCircleKm(fast.lat, fast.lon, lax.lat, lax.lon)
    const pace = cruiseSpeedKmh / (280 * 3.6)
    const e = estimate(f, fast.time + 30 * minute)
    expect(e.pace).toBeCloseTo(pace, 9)
    // 30 real minutes at 1008 km/h cover what the plan covers in 30 / pace minutes at 830.
    expect(greatCircleKm(fast.lat, fast.lon, e.lat, e.lon)).toBeCloseTo(504, 6)
    expect(e.plannedMinute).toBeCloseTo(profile.minutesAt(flown) + 30 / pace, 6)
    const toGo = (profile.minutesAt(profile.totalKm) - profile.minutesAt(flown)) * pace - 30
    expect(e.minutesToGo).toBeCloseTo(toGo, 6)

    // It arrives at the destination, low, as the landing time comes.
    const late = estimate(f, e.landing - minute)
    expect(late.phase).toBe('air')
    expect(greatCircleKm(late.lat, late.lon, lax.lat, lax.lon)).toBeLessThan(15)
    expect(late.altitudeFt).toBeLessThan(3000)
    expect(estimate(f, e.landing + minute).phase).toBe('landed')
  })

  it('flies the plan from a GPS takeoff when GPS is lost after the takeoff run', () => {
    const f = withFix(flight, onTheRunway)!
    const profile = flightProfile(f)
    const e = estimate(f, onTheRunway.time + 60 * minute)
    expect(e.basis).toBe('gps')
    expect(e.pace).toBe(1)
    // An hour of the planned climb and cruise, not 252 km at the 70 m/s of the takeoff run.
    const fromRunway = greatCircleKm(onTheRunway.lat, onTheRunway.lon, e.lat, e.lon)
    expect(fromRunway).toBeGreaterThan(650)
    expect(greatCircleKm(jfk.lat, jfk.lon, e.lat, e.lon)).toBeCloseTo(
      kmAtMinutes(profile, (onTheRunway.time + 60 * minute - f.takeoff!.time) / minute),
      0
    )
    expect(e.altitudeFt).toBe(35000)
    // Timed by GPS, not by the schedule: no schedule slack in how sure it is.
    expect(e.uncertaintyMinutes).toBeCloseTo(10, 6)
  })
})
