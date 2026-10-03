#!/usr/bin/env python3
"""
Sample GTGN EDR along the JFK -> LAX great-circle route.

Two variants:
  1. Constant FL350 cruise (all ~200 points sampled at the FL350 level).
  2. A simple climb/cruise/descent profile: linear climb from ~0 to
     FL350 over the first 150 km, cruise at FL350, linear descent from
     FL350 to ~0 over the last 150 km -- each point sampled at its
     nearest documented level.

Turbulence categories (medium/large aircraft, e.g. A320/737), calibrated
against 2,230 PIREPs for the 3 km grid sampled at a single grid cell
(calibrate.py; README "Category recalibration"):
  EDR <  0.12          -> Smooth / None
  0.12 <= EDR < 0.14   -> Light
  0.14 <= EDR < 0.44   -> Moderate
  0.44 <= EDR < 0.79   -> Severe
  EDR >= 0.79          -> Extreme
Severe/Extreme keep the GTGN User Guide's Figure 2 values (too few severe
PIREPs to fit them). The guide's full Medium (Large) row, 0.15 / 0.20 /
0.44 / 0.79, is kept as GUIDE_THRESHOLDS for comparison.
"""
import json

import numpy as np
from pyproj import Geod

import gtgn_common as gc

JFK = (40.6413, -73.7781)   # (lat, lon)
LAX = (33.9416, -118.4085)
N_POINTS = 200
CRUISE_FT = 35000
RAMP_KM = 150.0  # climb/descent distance at each end

# Medium/large-aircraft EDR thresholds from the GTGN User Guide, Figure 2,
# "AC weight class" = "Medium (Large)" (EDR*100 in the guide -> /100 here).
# Written for the old 13.5 km grid.
GUIDE_THRESHOLDS = {
    "Light": 0.15,
    "Moderate": 0.20,
    "Severe": 0.44,
    "Extreme": 0.79,
}

# Recalibrated for the 3 km grid against PIREPs (calibrate.py, 2026-10-03):
# the Light and Moderate cuts maximise the Peirce skill score (POD - POFD)
# for point-sampled GTGN and GTG F002. With the guide values the forecast
# caught only 11% of moderate PIREPs; with these, 44%.
MEDIUM_THRESHOLDS = {
    "Light": 0.12,
    "Moderate": 0.14,
    "Severe": GUIDE_THRESHOLDS["Severe"],
    "Extreme": GUIDE_THRESHOLDS["Extreme"],
}


def classify(edr, thresholds=MEDIUM_THRESHOLDS):
    if edr is None or np.isnan(edr):
        return "No data"
    if edr < thresholds["Light"]:
        return "Smooth"
    if edr < thresholds["Moderate"]:
        return "Light"
    if edr < thresholds["Severe"]:
        return "Moderate"
    if edr < thresholds["Extreme"]:
        return "Severe"
    return "Extreme"


def great_circle_points(n=N_POINTS):
    geod = Geod(ellps="sphere")
    lon1, lat1 = JFK[1], JFK[0]
    lon2, lat2 = LAX[1], LAX[0]
    total_dist_m = geod.inv(lon1, lat1, lon2, lat2)[2]
    pts = geod.npts(lon1, lat1, lon2, lat2, n - 2)
    lons = [lon1] + [p[0] for p in pts] + [lon2]
    lats = [lat1] + [p[1] for p in pts] + [lat2]
    # cumulative distance along the route for each point
    dists = [0.0]
    for i in range(1, len(lons)):
        d = geod.inv(lons[i - 1], lats[i - 1], lons[i], lats[i])[2]
        dists.append(dists[-1] + d)
    return np.array(lats), np.array(lons), np.array(dists) / 1000.0, total_dist_m / 1000.0


def nearest_level_ft(target_ft):
    arr = np.array(gc.LEVELS_FT)
    return int(arr[np.argmin(np.abs(arr - target_ft))])


def climb_cruise_descent_target_ft(dist_km, total_km, cruise_ft=CRUISE_FT, ramp_km=RAMP_KM):
    """Linear climb from ~0 to cruise_ft over the first ramp_km, cruise,
    linear descent to ~0 over the last ramp_km. Shared with
    route_forecast.py so both the nowcast and forecast route scripts use
    the same flight profile."""
    target_ft = np.full(len(dist_km), float(cruise_ft))
    climb_mask = dist_km < ramp_km
    target_ft[climb_mask] = cruise_ft * (dist_km[climb_mask] / ramp_km)
    descent_start = total_km - ramp_km
    descent_mask = dist_km > descent_start
    target_ft[descent_mask] = cruise_ft * ((total_km - dist_km[descent_mask]) / ramp_km)
    return np.clip(target_ft, 100, cruise_ft)


def sample_field(path, level_ft, i_idx, j_idx):
    msg = gc.message_index_for_level_ft(level_ft)
    vals, lats, lons, level_m, missing = gc.read_message(path, msg)
    nj, ni = vals.shape
    out = np.full(len(i_idx), np.nan)
    valid = (i_idx >= 0) & (i_idx < ni) & (j_idx >= 0) & (j_idx < nj)
    ii, jj = i_idx[valid], j_idx[valid]
    v = vals[jj, ii].astype(float)
    v[v == missing] = np.nan
    out[valid] = v
    return out


def main():
    path = gc.default_grib_path()
    lats, lons, dist_km, total_km = great_circle_points(N_POINTS)
    print(f"Route: JFK -> LAX, great circle, {len(lats)} points, total distance {total_km:.0f} km")

    p, x0, y0 = gc.grid_projector()
    i_idx, j_idx = gc.latlon_to_ij(lats, lons, p, x0, y0)

    # --- Variant 1: constant FL350 cruise -----------------------------
    edr_fl350 = sample_field(path, CRUISE_FT, i_idx, j_idx)

    # --- Variant 2: climb/cruise/descent profile ----------------------
    target_ft = climb_cruise_descent_target_ft(dist_km, total_km)

    # group points by nearest documented level so we only read each
    # GRIB message once
    nearest_ft = np.array([nearest_level_ft(t) for t in target_ft])
    edr_profile = np.full(len(dist_km), np.nan)
    for lvl in sorted(set(nearest_ft.tolist())):
        sel = nearest_ft == lvl
        edr_profile[sel] = sample_field(path, lvl, i_idx[sel], j_idx[sel])

    # --- Category breakdown (medium aircraft), constant-FL350 variant --
    cats = [classify(v) for v in edr_fl350]
    order = ["Smooth", "Light", "Moderate", "Severe", "Extreme", "No data"]
    counts = {c: cats.count(c) for c in order}
    n = len(cats)
    valid_edr = edr_fl350[~np.isnan(edr_fl350)]
    max_edr = float(np.nanmax(edr_fl350))
    max_idx = int(np.nanargmax(edr_fl350))

    print(f"\n=== FL350 cruise: EDR along route ===")
    print(f"Max EDR: {max_edr:.3f} m^(2/3)s^-1 at {dist_km[max_idx]:.0f} km "
          f"(lat {lats[max_idx]:.2f}, lon {lons[max_idx]:.2f})")
    print("Category breakdown (medium aircraft, e.g. A320/737):")
    for c in order:
        pct = 100.0 * counts[c] / n
        print(f"  {c:8s}: {counts[c]:3d}/{n} points ({pct:5.1f}%)")

    worst = max(("Extreme", "Severe", "Moderate", "Light", "Smooth"),
                key=lambda c: (counts[c] > 0, {"Extreme": 5, "Severe": 4, "Moderate": 3,
                                                "Light": 2, "Smooth": 1}[c]))
    verdicts = {
        "Extreme": "Expect a rough ride -- extreme turbulence is present somewhere on this route at FL350; "
                   "airlines would route around it.",
        "Severe": "Buckle up -- severe turbulence is present somewhere on this route at FL350; "
                  "expect a bumpy flight or a reroute.",
        "Moderate": "Some bumps likely -- moderate turbulence is present on part of the route at FL350; "
                    "keep your seatbelt fastened.",
        "Light": "Mostly smooth, with occasional light bumps at FL350 -- nothing to worry about.",
        "Smooth": "Smooth sailing -- no significant turbulence detected along the route at FL350.",
    }
    print(f"\nPassenger verdict: {verdicts[worst]}")

    print(f"\n=== Climb/cruise/descent profile: EDR along route ===")
    print(f"Max EDR: {np.nanmax(edr_profile):.3f} m^(2/3)s^-1")

    # --- save results for the plotting script -------------------------
    np.savez(
        gc.DATA_DIR / "route_samples.npz",
        lats=lats, lons=lons, dist_km=dist_km, total_km=total_km,
        edr_fl350=edr_fl350, edr_profile=edr_profile, target_ft=target_ft,
    )
    summary = {
        "file": path.name,
        "n_points": n,
        "max_edr_fl350": max_edr,
        "max_edr_profile": float(np.nanmax(edr_profile)),
        "category_counts_fl350": counts,
        "category_pct_fl350": {c: round(100.0 * counts[c] / n, 1) for c in order},
        "verdict": verdicts[worst],
        "thresholds_medium_aircraft": MEDIUM_THRESHOLDS,
        "thresholds_source": "Light/Moderate calibrated against PIREPs (calibrate.py); "
                              "Severe/Extreme from the GTGN User Guide, Figure 2",
    }
    with open(gc.DATA_DIR / "route_summary.json", "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\nSaved samples to data/route_samples.npz and summary to data/route_summary.json")


if __name__ == "__main__":
    main()
