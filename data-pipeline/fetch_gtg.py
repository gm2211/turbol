#!/usr/bin/env python3
"""
Fetch a small subset of NOAA's GTG v4 forecast (DAFS) messages needed to
answer "how bumpy will my JFK->LAX flight be" -- WITHOUT downloading the
~150-160 MB full forecast-hour files.

Directory layout discovered by listing NOMADS (no HH sub-directory, unlike
GTGN):
  https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod/dafs.YYYYMMDD/dafs.tHHz.gtg.3km.conus.fFFF.grib2[.idx]
  - Hourly cycles (t00z..t23z), forecast hours f000..f018.
  - Each .grib2 file has a matching .idx (curl --http1.1, small text file)
    giving "message#:byte_offset:date:PARAM:level:step" for every
    message -- exactly what's needed to compute HTTP Range requests.

Fields found in the .idx (verified against dafs.t16z.gtg.3km.conus.f001):
  - MXEDPRM: 1 message, "entire atmosphere" -- a column-max composite
    turbulence index. Not per-level, so not useful for altitude-specific
    sampling; NOT fetched here.
  - EDPARM: 51 messages, one per the same 100ft/1000ft-step level ladder
    as GTGN, discipline=0/category=19/number=30 -- IDENTICAL GRIB2
    identity to GTGN's own field. This is GTG's combined/blended EDR
    forecast (the analogue of GTGN's nowcast field). *** This is the
    field we use. ***
  - CATEDR: 51 messages, same levels -- the clear-air-turbulence
    *component* EDR (one of the inputs blended into EDPARM). Not used.
  - MWTURB: 51 messages, same levels -- the mountain-wave-turbulence
    *component* EDR (the other input blended into EDPARM). Not used.
  - An unnamed field (idx shows "var discipline=0 master_table=2
    parmcat=19 parm=50", 51 messages): eccodes has no local table entry
    for it either. We range-fetched one message (30 m level, 233 KB) to
    inspect it out of curiosity -- min/max values were exactly {0, 9999},
    i.e. a binary flag/mask field, not an EDR value. Not identified
    further and NOT used.

  We use EDPARM because it (a) matches GTGN's own field exactly, letting
  us do an apples-to-apples GTGN-vs-GTG comparison, and (b) is the
  "combined" turbulence estimate the task asked for, rather than the
  separate CAT/MWT components.

Subsetting: for each needed forecast hour we Range-fetch ONLY the EDPARM
messages at a coarse altitude ladder (COARSE_LEVELS_FT below, 8 levels
out of the 51 available) via `curl --http1.1 -r start-end`, and
concatenate just those messages into one small local file per forecast
hour (valid GRIB2: multiple messages back-to-back is a normal multi-
message file eccodes reads sequentially). Total request budget is
capped well under the 300 MB task ceiling -- see running total printed
at the end.
"""
import json
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import gtgn_common as gc

BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod"
DATA_DIR = Path(__file__).parent / "data"
GTG_DIR = DATA_DIR / "gtg"

# Coarse altitude ladder: cruise level (FL350) plus a handful of levels
# spanning the climb/descent ramps. 8 of the 51 available levels.
COARSE_LEVELS_FT = [100, 5000, 10000, 15000, 20000, 25000, 30000, 35000]

# Flight assumptions from the task: ~3980 km JFK-LAX great circle,
# 830 km/h cruise ground speed, +20 min for climb/descent => ~5.3 h.
# Departure is assumed ~1 h after the latest complete forecast cycle.
FLIGHT_HOURS_NEEDED = list(range(1, 8))  # F001..F007

_byte_counter = {"total": 0}


def list_dir(url: str) -> str:
    result = subprocess.run(
        ["curl", "--http1.1", "-s", "--fail", url],
        capture_output=True, text=True, timeout=30,
    )
    return result.stdout if result.returncode == 0 else ""


def find_latest_complete_cycle():
    """Walk backwards from now (UTC) to find the most recent DAFS cycle
    that has a complete f000..f018 set of gtg.3km.conus files."""
    now = datetime.now(timezone.utc)
    for day_offset in (0, 1):
        day = now - timedelta(days=day_offset)
        day_str = day.strftime("%Y%m%d")
        day_url = f"{BASE}/dafs.{day_str}/"
        listing = list_dir(day_url)
        if not listing:
            continue
        cycles = sorted(set(re.findall(
            r'href="dafs\.t(\d{2})z\.gtg\.3km\.conus\.f000\.grib2"', listing
        )), reverse=True)
        if day_offset == 0:
            cycles = [c for c in cycles if int(c) <= now.hour]
        for hh in cycles:
            if f'href="dafs.t{hh}z.gtg.3km.conus.f018.grib2"' in listing:
                cycle_dt = day.replace(hour=int(hh), minute=0, second=0, microsecond=0)
                return day_str, hh, cycle_dt, day_url
    return None


def fetch_text(url: str) -> str:
    result = subprocess.run(
        ["curl", "--http1.1", "-s", "--fail", url],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(f"failed to fetch {url}")
    return result.stdout


def parse_idx(idx_text: str):
    """Return list of (msg_num, offset, param, level_str) tuples."""
    rows = []
    for line in idx_text.strip().splitlines():
        parts = line.split(":")
        if len(parts) < 5:
            continue
        msg_num = int(parts[0])
        offset = int(parts[1])
        param = parts[3]
        level = parts[4]
        rows.append((msg_num, offset, param, level))
    return rows


def range_fetch(url: str, start: int, end: int, out_path: Path):
    """Append bytes [start, end] (inclusive) of url to out_path via
    HTTP Range request over HTTP/1.1."""
    result = subprocess.run(
        ["curl", "--http1.1", "-s", "--fail", "-r", f"{start}-{end}", url],
        capture_output=True, timeout=60,
    )
    if result.returncode != 0 or not result.stdout:
        raise RuntimeError(f"range fetch failed for {url} [{start}-{end}]")
    with open(out_path, "ab") as f:
        f.write(result.stdout)
    _byte_counter["total"] += len(result.stdout)
    return len(result.stdout)


def fetch_hour(day_url: str, hh: str, fhour: int):
    """Fetch (or reuse cached) EDPARM messages at COARSE_LEVELS_FT for
    one forecast hour, into data/gtg/fXXX.grib2."""
    fstr = f"f{fhour:03d}"
    grib_name = f"dafs.t{hh}z.gtg.3km.conus.{fstr}.grib2"
    grib_url = f"{day_url}{grib_name}"
    out_path = GTG_DIR / f"{fstr}.grib2"
    meta_path = GTG_DIR / f"{fstr}.meta.json"

    if out_path.exists() and meta_path.exists():
        meta = json.loads(meta_path.read_text())
        if meta.get("levels_ft") == COARSE_LEVELS_FT:
            print(f"{fstr}: cached ({out_path.stat().st_size/1e3:.0f} KB) -- skipping")
            return

    print(f"{fstr}: fetching .idx for {grib_name}")
    idx_text = fetch_text(grib_url + ".idx")
    rows = parse_idx(idx_text)

    # Build offset->next_offset map so we know each message's byte length.
    offsets_in_order = [(num, off) for num, off, _, _ in rows]
    next_offset = {}
    for i in range(len(offsets_in_order) - 1):
        next_offset[offsets_in_order[i][0]] = offsets_in_order[i + 1][1]

    wanted_levels_m = {gc.level_m_for_ft(ft): ft for ft in COARSE_LEVELS_FT}
    edparm_rows = [(num, off, level) for num, off, param, level in rows if param == "EDPARM"]

    if out_path.exists():
        out_path.unlink()

    fetched_levels = []
    hour_bytes = 0
    for num, off, level in edparm_rows:
        m = re.match(r"(\d+) m above mean sea level", level)
        if not m:
            continue
        level_m = int(m.group(1))
        if level_m not in wanted_levels_m:
            continue
        end_off = next_offset.get(num)
        if end_off is None:
            raise RuntimeError(f"no next-offset for message {num} (unexpected: last in file?)")
        n = range_fetch(grib_url, off, end_off - 1, out_path)
        hour_bytes += n
        fetched_levels.append(wanted_levels_m[level_m])

    fetched_levels.sort()
    assert fetched_levels == sorted(COARSE_LEVELS_FT), (
        f"expected levels {COARSE_LEVELS_FT}, got {fetched_levels}"
    )
    meta_path.write_text(json.dumps({
        "cycle": f"{hh}z", "forecast_hour": fhour, "levels_ft": COARSE_LEVELS_FT,
        "bytes": hour_bytes, "source_url": grib_url,
    }, indent=2))
    print(f"{fstr}: fetched {hour_bytes/1e6:.2f} MB across {len(fetched_levels)} levels")


def main():
    found = find_latest_complete_cycle()
    if not found:
        print("Could not find a complete DAFS cycle in the last two UTC days.", file=sys.stderr)
        sys.exit(1)
    day_str, hh, cycle_dt, day_url = found
    print(f"Latest complete DAFS/GTG cycle: dafs.{day_str} t{hh}z (init {cycle_dt.isoformat()})")

    departure_dt = cycle_dt + timedelta(hours=1)
    print(f"Assumed departure: cycle + 1h = {departure_dt.isoformat()}")
    print(f"Forecast hours to fetch (F001-F007, per task spec): {FLIGHT_HOURS_NEEDED}")
    print(f"Coarse altitude ladder (ft): {COARSE_LEVELS_FT}")

    GTG_DIR.mkdir(parents=True, exist_ok=True)
    for fhour in FLIGHT_HOURS_NEEDED:
        fetch_hour(day_url, hh, fhour)

    manifest = {
        "cycle_day": day_str, "cycle_hour": hh, "cycle_init_utc": cycle_dt.isoformat(),
        "departure_utc": departure_dt.isoformat(), "forecast_hours": FLIGHT_HOURS_NEEDED,
        "levels_ft": COARSE_LEVELS_FT, "total_bytes_this_run": _byte_counter["total"],
    }
    (GTG_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"\nTotal bytes downloaded this run: {_byte_counter['total']/1e6:.2f} MB "
          f"(cap: ~300 MB)")


if __name__ == "__main__":
    main()
