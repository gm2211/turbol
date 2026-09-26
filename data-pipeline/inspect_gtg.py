#!/usr/bin/env python3
"""Print a summary of the fetched GTG/DAFS forecast subset files: fields
present (from the .idx survey), grid identity vs. GTGN, forecast-hour
metadata, and EDR stats per fetched hour at FL350."""
import json

import numpy as np

import gtgn_common as gc

GTG_DIR = gc.DATA_DIR / "gtg"


def main():
    manifest = json.loads((GTG_DIR / "manifest.json").read_text())
    print("=== GTG v4 forecast (DAFS) subset summary ===")
    print(f"Cycle: dafs.{manifest['cycle_day']} t{manifest['cycle_hour']}z "
          f"(init {manifest['cycle_init_utc']})")
    print(f"Assumed departure: {manifest['departure_utc']}")
    print(f"Forecast hours fetched: {manifest['forecast_hours']}")
    print(f"Levels fetched (ft): {manifest['levels_ft']}")

    print("\nFields found in the .idx survey of dafs.t16z...f001.grib2.idx "
          "(205 messages total):")
    print("  MXEDPRM  (1 msg,  'entire atmosphere')      -- column-max composite; not per-level, unused")
    print("  EDPARM   (51 msgs, per-level)               -- combined/blended EDR forecast; "
          "SAME discipline=0/cat=19/num=30 as GTGN -- *** used here ***")
    print("  CATEDR   (51 msgs, per-level)               -- clear-air-turbulence component; unused")
    print("  MWTURB   (51 msgs, per-level)                -- mountain-wave-turbulence component; unused")
    print("  disc=0/cat=19/num=50 (51 msgs, per-level)   -- unnamed in eccodes tables; sampled one "
          "message (30m level, 233KB): values were exactly {0, 9999} (binary flag/mask, not EDR); unused")

    print(f"\nPer-forecast-hour files: {sorted(GTG_DIR.glob('f0*.grib2'))}")
    total_bytes = sum(p.stat().st_size for p in GTG_DIR.glob("f0*.grib2"))
    print(f"Total cached GTG bytes on disk: {total_bytes/1e6:.2f} MB")

    # Inspect one file in full detail (grid identity + per-message stats)
    sample_path = GTG_DIR / "f001.grib2"
    msgs = gc.read_all_messages(sample_path)
    print(f"\n--- f001.grib2: {len(msgs)} messages ---")
    m0 = msgs[0]
    print(f"Grid shape (Nj,Ni) = {m0['vals'].shape}  "
          f"(GTGN grid is (1059,1799) -- {'MATCH' if m0['vals'].shape == (1059, 1799) else 'DIFFERENT'})")
    print(f"Lat range: {m0['lats'].min():.3f} to {m0['lats'].max():.3f}")
    print(f"Lon range: {m0['lons'].min():.3f} to {m0['lons'].max():.3f}")
    print(f"forecastTime (hours) = {m0['forecast_hour']}, "
          f"dataDate/dataTime (cycle init) = {m0['data_date']}/{m0['data_time']}")

    for m in msgs:
        vals = m["vals"]
        mask = vals != m["missing"]
        v = vals[mask]
        ft_level = gc.LEVELS_FT[gc.LEVELS_M.index(m["level_m"])]
        print(f"  level {m['level_m']:6d} m ({ft_level:>6} ft): "
              f"min={v.min():.3f} max={v.max():.3f} "
              f"missing={100*(1-mask.sum()/vals.size):.1f}%")

    print("\nSummary across all 7 fetched forecast hours, FL350 (35000 ft) level:")
    for fhour in manifest["forecast_hours"]:
        msgs = gc.read_all_messages(GTG_DIR / f"f{fhour:03d}.grib2")
        fl350 = next(m for m in msgs if m["level_m"] == gc.level_m_for_ft(35000))
        vals = fl350["vals"]
        mask = vals != fl350["missing"]
        v = vals[mask]
        print(f"  F{fhour:03d}: min={v.min():.3f} max={v.max():.3f} mean={v.mean():.3f}")


if __name__ == "__main__":
    main()
