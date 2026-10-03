#!/usr/bin/env python3
"""
4-D (space + time) turbulence sampling for a JFK -> LAX flight departing
~1 hour after the latest complete GTG v4 (DAFS) forecast cycle.

For each of the 200 great-circle route points we:
  1. Estimate the clock time the aircraft is over that point (departure
     time + elapsed flight time, using the same climb/cruise/descent
     profile as route.py plus a simple climb/descent time penalty).
  2. Convert that to a (possibly fractional) GTG forecast hour and
     linearly interpolate in time between the two bracketing fetched
     forecast-hour files.
  3. Snap the target altitude to the nearest fetched coarse level and
     look up EDR at the point's nearest grid cell (same Lambert
     Conformal projection as GTGN -- verified identical grid in
     inspect_gtg.py).

Classification uses the same medium-aircraft EDR thresholds as route.py
(0.12 / 0.14 / 0.44 / 0.79, calibrated against PIREPs by calibrate.py).

Two refinements on top of the raw forecast:
  - Near-ground points (target altitude below NEAR_GROUND_FT, i.e. the
    takeoff roll / final approach) are sampled but kept out of the
    category breakdown and the worst bump: the cruise EDR thresholds are
    questionable there, and the 100 ft level was dominating the result.
    Their max is reported separately.
  - For the first BLEND_HOURS of the flight, EDR is a linear blend of the
    GTGN nowcast valid at departure (weight 1 at takeoff, 0 at
    BLEND_HOURS) and the GTG forecast. GTGN folds in live observations,
    so it beats the forecast early on; the forecast takes over as the
    nowcast ages.

Also runs a GTGN-vs-GTG sanity check (task step 4): fetches (if not
already cached) the single GTGN nowcast file whose valid time exactly
matches the GTG F001 forecast valid time, and compares EDR at FL350
along the route between the two products.
"""
import json
import subprocess
from datetime import timedelta, datetime, timezone

import numpy as np

import gtgn_common as gc
from route import (JFK, LAX, N_POINTS, CRUISE_FT, RAMP_KM, MEDIUM_THRESHOLDS,
                    classify, great_circle_points, climb_cruise_descent_target_ft, sample_field)
from fetch_gtg import COARSE_LEVELS_FT, GTG_DIR

CRUISE_SPEED_KMH = 830.0
CLIMB_DESCENT_PENALTY_H = 20.0 / 60.0  # 20 min total, split 10/10 (task assumption)
NEAR_GROUND_FT = 2000.0  # below this, cruise thresholds don't apply (see docstring)
BLEND_HOURS = 1.0  # GTGN nowcast weight tapers from 1 to 0 over this much flight time
GTGN_LOOKBACK_STEPS = 4  # if the exact-time GTGN file is missing, try up to 1h earlier


def nearest_coarse_level_ft(target_ft):
    arr = np.array(COARSE_LEVELS_FT)
    return int(arr[np.argmin(np.abs(arr - target_ft))])


def elapsed_hours(dist_km, total_km, cruise_speed=CRUISE_SPEED_KMH,
                   ramp_km=RAMP_KM, penalty_h=CLIMB_DESCENT_PENALTY_H):
    """Elapsed flight time (hours) at distance dist_km along the route:
    base cruise-speed time plus a climb/descent time penalty that
    accrues linearly across each ramp (10 min climb + 10 min descent)."""
    base = dist_km / cruise_speed
    half_penalty = penalty_h / 2.0
    extra = np.zeros_like(dist_km)
    climb_mask = dist_km < ramp_km
    extra[climb_mask] = half_penalty * (dist_km[climb_mask] / ramp_km)
    mid_mask = (dist_km >= ramp_km) & (dist_km <= total_km - ramp_km)
    extra[mid_mask] = half_penalty
    descent_start = total_km - ramp_km
    descent_mask = dist_km > descent_start
    extra[descent_mask] = half_penalty + half_penalty * (
        (dist_km[descent_mask] - descent_start) / ramp_km)
    return base + extra


def load_hour_levels(fhour):
    """Return {level_ft: message_dict} for one fetched forecast hour."""
    msgs = gc.read_all_messages(GTG_DIR / f"f{fhour:03d}.grib2")
    by_level_m = {m["level_m"]: m for m in msgs}
    return {ft: by_level_m[gc.level_m_for_ft(ft)] for ft in COARSE_LEVELS_FT}


def fetch_matching_gtgn(target_dt):
    """Download (if not already cached) the single GTGN file whose valid
    time matches target_dt (rounded to the nearest 15 minutes), stepping
    back 15 minutes at a time if that file isn't published. Used for the
    first-hour blend and the sanity-check comparison. Reuses fetch.py's
    politeness conventions (curl --http1.1, cache, one file)."""
    rounded_min = 15 * round(target_dt.minute / 15) % 60
    hour_carry = 1 if (target_dt.minute >= 53) else 0  # rounds 53-59 up into next hour
    dt = target_dt.replace(minute=0, second=0, microsecond=0) + timedelta(hours=hour_carry)
    dt = dt.replace(minute=rounded_min)
    for step in range(GTGN_LOOKBACK_STEPS + 1):
        try:
            return fetch_gtgn_at(dt - timedelta(minutes=15 * step))
        except RuntimeError as e:
            print(f"  {e}")
    raise RuntimeError(f"no GTGN file within {GTGN_LOOKBACK_STEPS * 15} min before {dt.isoformat()}")


def fetch_gtgn_at(dt):
    """Download (if not cached) the GTGN file valid at dt (a 15-minute mark)."""
    day_str = dt.strftime("%Y%m%d")
    hh = dt.strftime("%H")
    fname = f"gtgn.t{hh}{dt.minute:02d}z.3km.grib2"
    dest = gc.DATA_DIR / fname
    if dest.exists() and dest.stat().st_size > 0:
        print(f"GTGN file already cached: {dest.name}")
        return dest, dt
    url = (f"https://nomads.ncep.noaa.gov/pub/data/nccf/com/gtgn/prod/"
           f"gtgn.{day_str}/{hh}/{fname}")
    print(f"Fetching GTGN file: {url}")
    tmp = dest.with_suffix(dest.suffix + ".part")
    result = subprocess.run(["curl", "--http1.1", "-s", "--fail", "-o", str(tmp), url], timeout=180)
    if result.returncode != 0 or not tmp.exists():
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"GTGN download failed for {url}")
    tmp.rename(dest)
    print(f"Saved {dest} ({dest.stat().st_size/1e6:.1f} MB)")
    return dest, dt


def main():
    manifest = json.loads((GTG_DIR / "manifest.json").read_text())
    cycle_init = datetime.fromisoformat(manifest["cycle_init_utc"])
    departure = datetime.fromisoformat(manifest["departure_utc"])
    fetched_hours = manifest["forecast_hours"]
    print(f"GTG cycle init: {cycle_init.isoformat()}  Departure: {departure.isoformat()}")

    lats, lons, dist_km, total_km = great_circle_points(N_POINTS)
    target_ft = climb_cruise_descent_target_ft(dist_km, total_km)
    snapped_ft = np.array([nearest_coarse_level_ft(t) for t in target_ft])

    elapsed_h = elapsed_hours(dist_km, total_km)
    eta = [departure + timedelta(hours=float(h)) for h in elapsed_h]
    # forecast hour relative to cycle init (departure is cycle+1h)
    fcst_hour_float = np.array([(t - cycle_init).total_seconds() / 3600.0 for t in eta])
    print(f"Flight forecast-hour range needed: {fcst_hour_float.min():.2f} to {fcst_hour_float.max():.2f} "
          f"(fetched: {fetched_hours})")
    assert fcst_hour_float.max() <= max(fetched_hours), "route needs a forecast hour we didn't fetch"
    assert fcst_hour_float.min() >= min(fetched_hours), "route needs a forecast hour we didn't fetch"

    p, x0, y0 = gc.grid_projector()
    i_idx, j_idx = gc.latlon_to_ij(lats, lons, p, x0, y0)

    # Preload all fetched hours once.
    hour_cache = {h: load_hour_levels(h) for h in fetched_hours}

    gtg_edr = np.full(N_POINTS, np.nan)
    for k in range(N_POINTS):
        h_lo = int(np.floor(fcst_hour_float[k]))
        h_hi = int(np.ceil(fcst_hour_float[k]))
        h_lo = max(h_lo, min(fetched_hours))
        h_hi = min(h_hi, max(fetched_hours))
        frac = 0.0 if h_hi == h_lo else (fcst_hour_float[k] - h_lo) / (h_hi - h_lo)
        lvl = int(snapped_ft[k])
        ii, jj = i_idx[k], j_idx[k]

        def lookup(hour):
            m = hour_cache[hour][lvl]
            v = m["vals"][jj, ii]
            return np.nan if v == m["missing"] else float(v)

        v_lo = lookup(h_lo)
        v_hi = lookup(h_hi) if h_hi != h_lo else v_lo
        if np.isnan(v_lo) or np.isnan(v_hi):
            gtg_edr[k] = v_lo if not np.isnan(v_lo) else v_hi
        else:
            gtg_edr[k] = v_lo + frac * (v_hi - v_lo)

    # --- Blend the GTGN nowcast (valid at departure) into the first hour ---
    gtgn_path, gtgn_valid = fetch_matching_gtgn(departure)
    gtgn_age_h = (departure - gtgn_valid).total_seconds() / 3600.0
    gtgn_weight = np.clip(1.0 - elapsed_h / BLEND_HOURS, 0.0, 1.0)
    gtgn_edr = np.full(N_POINTS, np.nan)
    for lvl in np.unique(snapped_ft[gtgn_weight > 0]):
        at_lvl = (snapped_ft == lvl) & (gtgn_weight > 0)
        gtgn_edr[at_lvl] = sample_field(gtgn_path, int(lvl), i_idx, j_idx)[at_lvl]
    blended = (gtgn_weight > 0) & ~np.isnan(gtgn_edr)
    edr = gtg_edr.copy()
    both = blended & ~np.isnan(gtg_edr)
    edr[both] = gtgn_weight[both] * gtgn_edr[both] + (1.0 - gtgn_weight[both]) * gtg_edr[both]
    only_gtgn = blended & np.isnan(gtg_edr)
    edr[only_gtgn] = gtgn_edr[only_gtgn]
    print(f"GTGN blend: {gtgn_path.name} (valid {gtgn_valid.isoformat()}, "
          f"{gtgn_age_h * 60:.0f} min before departure) over the first {BLEND_HOURS:g}h, "
          f"{blended.sum()} points blended")

    near_ground = target_ft < NEAR_GROUND_FT
    cats = ["Near ground" if ng else classify(v) for v, ng in zip(edr, near_ground)]
    order = ["Smooth", "Light", "Moderate", "Severe", "Extreme", "No data"]
    counts = {c: cats.count(c) for c in order}
    n = int((~near_ground).sum())
    en_route_edr = np.where(near_ground, np.nan, edr)
    max_idx = int(np.nanargmax(en_route_edr))
    max_edr = float(edr[max_idx])
    near_ground_max = (float(np.nanmax(edr[near_ground]))
                       if (near_ground & ~np.isnan(edr)).any() else None)
    worst_elapsed_h = elapsed_h[max_idx]
    worst_h = int(worst_elapsed_h)
    worst_m = int(round((worst_elapsed_h - worst_h) * 60))

    print(f"\n=== 4-D forecast sampling along JFK -> LAX ===")
    print(f"Max EDR: {max_edr:.3f} m^(2/3)s^-1, ~{worst_h}h{worst_m:02d}m after takeoff, "
          f"over lat {lats[max_idx]:.2f} lon {lons[max_idx]:.2f} "
          f"(dist {dist_km[max_idx]:.0f} km from JFK, target level {int(snapped_ft[max_idx])} ft)")
    if near_ground_max is not None:
        print(f"Near ground (< {NEAR_GROUND_FT:.0f} ft, {int(near_ground.sum())} points, not categorised): "
              f"max EDR {near_ground_max:.3f}")
    print(f"Category breakdown (medium aircraft, {n} en-route points):")
    for c in order:
        pct = 100.0 * counts[c] / n
        print(f"  {c:8s}: {counts[c]:3d}/{n} ({pct:5.1f}%)")

    worst = max(("Extreme", "Severe", "Moderate", "Light", "Smooth"),
                key=lambda c: (counts[c] > 0, {"Extreme": 5, "Severe": 4, "Moderate": 3,
                                                "Light": 2, "Smooth": 1}[c]))
    verdicts = {
        "Extreme": "Expect a rough ride -- extreme turbulence is forecast somewhere on this flight; "
                   "airlines would route around it.",
        "Severe": "Buckle up -- severe turbulence is forecast somewhere on this flight; "
                  "expect a bumpy ride or a reroute.",
        "Moderate": f"Some bumps likely, worst around {worst_h}h{worst_m:02d}m after takeoff -- "
                    "keep your seatbelt fastened.",
        "Light": "Mostly smooth, with occasional light bumps forecast -- nothing to worry about.",
        "Smooth": "Smooth sailing forecast -- no significant turbulence expected on this flight.",
    }
    verdict = verdicts[worst]
    print(f"\nPassenger verdict: {verdict}")

    np.savez(gc.DATA_DIR / "route_forecast_samples.npz",
             lats=lats, lons=lons, dist_km=dist_km, total_km=total_km,
             edr=edr, gtg_edr=gtg_edr, gtgn_edr=gtgn_edr, gtgn_weight=gtgn_weight,
             near_ground=near_ground, target_ft=snapped_ft, elapsed_h=elapsed_h,
             fcst_hour_float=fcst_hour_float)
    summary = {
        "cycle_init_utc": manifest["cycle_init_utc"], "departure_utc": manifest["departure_utc"],
        "max_edr": max_edr, "worst_time_after_departure": f"{worst_h}h{worst_m:02d}m",
        "near_ground_ft": NEAR_GROUND_FT, "near_ground_points": int(near_ground.sum()),
        "near_ground_max_edr": near_ground_max,
        "gtgn_blend": {"file": gtgn_path.name, "valid_utc": gtgn_valid.isoformat(),
                       "blend_hours": BLEND_HOURS, "points_blended": int(blended.sum())},
        "category_counts": counts,
        "category_pct": {c: round(100.0 * counts[c] / n, 1) for c in order},
        "verdict": verdict, "thresholds_medium_aircraft": MEDIUM_THRESHOLDS,
    }
    with open(gc.DATA_DIR / "route_forecast_summary.json", "w") as f:
        json.dump(summary, f, indent=2)
    print("Saved data/route_forecast_samples.npz and data/route_forecast_summary.json")

    # --- Sanity check: GTGN nowcast vs GTG F001 forecast, both at FL350 ---
    print("\n=== Sanity check: GTGN nowcast vs GTG forecast, FL350, same valid time ===")
    f001_valid = cycle_init + timedelta(hours=1)  # = departure; GTGN file fetched above
    print(f"GTG F001 valid: {f001_valid.isoformat()}   GTGN file valid: {gtgn_valid.isoformat()} "
          f"({gtgn_path.name})")

    gtgn_vals, gtgn_lats, gtgn_lons, _, gtgn_missing = gc.read_message(
        gtgn_path, gc.message_index_for_level_ft(35000))
    gtg_f001_fl350 = hour_cache[1][35000]

    gtgn_sample = np.array([np.nan if gtgn_vals[j, i] == gtgn_missing else gtgn_vals[j, i]
                             for i, j in zip(i_idx, j_idx)])
    gtg_sample = np.array([np.nan if gtg_f001_fl350["vals"][j, i] == gtg_f001_fl350["missing"]
                            else gtg_f001_fl350["vals"][j, i] for i, j in zip(i_idx, j_idx)])

    both = ~np.isnan(gtgn_sample) & ~np.isnan(gtg_sample)
    corr = float(np.corrcoef(gtgn_sample[both], gtg_sample[both])[0, 1])
    mean_diff = float(np.mean(gtg_sample[both] - gtgn_sample[both]))
    mae = float(np.mean(np.abs(gtg_sample[both] - gtgn_sample[both])))
    print(f"Route-sampled FL350 comparison ({both.sum()}/{N_POINTS} points with data in both):")
    print(f"  correlation (GTGN vs GTG F001): {corr:.3f}")
    print(f"  mean diff (GTG - GTGN): {mean_diff:+.3f} m^(2/3)s^-1")
    print(f"  mean absolute diff: {mae:.3f} m^(2/3)s^-1")
    print(f"  GTGN max: {np.nanmax(gtgn_sample):.3f}   GTG F001 max: {np.nanmax(gtg_sample):.3f}")

    comparison = {
        "gtg_f001_valid_utc": f001_valid.isoformat(), "gtgn_valid_utc": gtgn_valid.isoformat(),
        "gtgn_file": gtgn_path.name, "n_points_compared": int(both.sum()),
        "correlation": corr, "mean_diff_gtg_minus_gtgn": mean_diff, "mean_abs_diff": mae,
        "gtgn_max": float(np.nanmax(gtgn_sample)), "gtg_f001_max": float(np.nanmax(gtg_sample)),
    }
    with open(gc.DATA_DIR / "gtgn_vs_gtg_comparison.json", "w") as f:
        json.dump(comparison, f, indent=2)
    print("Saved data/gtgn_vs_gtg_comparison.json")

    # Persist the FL350 comparison curves too, for plot_forecast.py to overlay.
    np.savez(gc.DATA_DIR / "gtgn_vs_gtg_fl350_curves.npz",
             dist_km=dist_km, elapsed_h=elapsed_h, gtgn_fl350=gtgn_sample, gtg_f001_fl350=gtg_sample)


if __name__ == "__main__":
    main()
