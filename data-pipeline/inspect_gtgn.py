#!/usr/bin/env python3
"""Print a summary of the cached GTGN GRIB2 file: message count, level
ladder, param identity, grid shape/extent, and EDR stats at FL350."""
import eccodes
import numpy as np

import gtgn_common as gc


def main():
    path = gc.default_grib_path()
    print(f"File: {path}  ({path.stat().st_size / 1e6:.1f} MB)\n")

    # --- message-level scan: count, param id, level ladder -----------
    levels_m = []
    with open(path, "rb") as f:
        first_keys = None
        count = 0
        while True:
            gid = eccodes.codes_grib_new_from_file(f)
            if gid is None:
                break
            count += 1
            if first_keys is None:
                first_keys = {
                    k: eccodes.codes_get(gid, k)
                    for k in ("discipline", "parameterCategory", "parameterNumber",
                              "typeOfFirstFixedSurface", "gridType", "Ni", "Nj",
                              "DxInMetres", "DyInMetres", "Latin1InDegrees",
                              "Latin2InDegrees", "LoVInDegrees", "LaDInDegrees",
                              "shapeOfTheEarth", "missingValue")
                }
            levels_m.append(eccodes.codes_get(gid, "scaledValueOfFirstFixedSurface"))
            eccodes.codes_release(gid)

    print(f"Message count: {count}")
    print("GRIB2 identity: discipline={discipline} category={parameterCategory} "
          "number={parameterNumber}  (= EDPARM / Eddy Dissipation Parameter, "
          "per GTGN User Guide; eccodes has no local table entry so "
          "shortName/units decode as 'unknown' -- units are m^(2/3) s^-1 "
          "per the guide)".format(**first_keys))
    print(f"typeOfFirstFixedSurface = {first_keys['typeOfFirstFixedSurface']} "
          "(102 = specific altitude above mean sea level)")
    print(f"Grid: {first_keys['gridType']}  Ni x Nj = {first_keys['Ni']} x {first_keys['Nj']}  "
          f"Dx=Dy={first_keys['DxInMetres']} m")
    print(f"  Latin1={first_keys['Latin1InDegrees']} Latin2={first_keys['Latin2InDegrees']} "
          f"LaD={first_keys['LaDInDegrees']} LoV={first_keys['LoVInDegrees']} "
          f"shapeOfTheEarth={first_keys['shapeOfTheEarth']} (6=spherical R=6371229m)")
    print(f"missingValue flag = {first_keys['missingValue']}")

    levels_ft_raw = [m / 0.3048 for m in levels_m]
    levels_ft = [round(m / 0.3048) for m in levels_m]
    print(f"\nLevel ladder ({len(levels_ft)} levels), first/last 5 (ft, rounded): "
          f"{levels_ft[:5]} ... {levels_ft[-5:]}")
    # GRIB stores the surface value in whole metres, so the round-trip to
    # feet is off by a foot or two (e.g. 30 m = 98.4 ft, not exactly 100).
    # Compare against the documented ladder within a few feet of tolerance.
    max_err = max(abs(a - b) for a, b in zip(levels_ft_raw, gc.LEVELS_FT))
    assert max_err < 5, f"level ladder does not match documented ladder (max err {max_err} ft)"
    print(f"Matches documented ladder: 100 ft, then 1000-50000 ft every 1000 ft "
          f"(max rounding error {max_err:.1f} ft from metre storage).  OK")

    # --- FL350 slice: grid extent + value stats -----------------------
    idx = gc.message_index_for_level_ft(35000)
    vals, lats, lons, level_m, missing = gc.read_message(path, idx)
    print(f"\nFL350 message index = {idx}, level = {level_m} m ({level_m/0.3048:.0f} ft)")
    print(f"Array shape (Nj,Ni) = {vals.shape}")
    print(f"Lat extent: {lats.min():.3f} to {lats.max():.3f} deg N")
    print(f"Lon extent: {lons.min():.3f} to {lons.max():.3f} deg E (CONUS + margins)")

    mask = vals != missing
    frac_missing = 1 - mask.sum() / vals.size
    v = vals[mask]
    pct = np.percentile(v, [50, 90, 99, 99.9, 100])
    print(f"\nEDR at FL350: {frac_missing*100:.1f}% flagged missing (value == {missing})")
    print(f"  min={v.min():.3f}  max={v.max():.3f}  units m^(2/3) s^-1")
    print(f"  percentiles [50,90,99,99.9,100] = {np.round(pct, 3).tolist()}")


if __name__ == "__main__":
    main()
