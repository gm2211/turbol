#!/usr/bin/env python3
"""
Recalibrate the turbulence category thresholds for the 3 km GTG data
against pilot reports (PIREPs).

The thresholds in route.py (0.15 / 0.20 / 0.44 / 0.79) come from the GTGN
User Guide, written for the old 13.5 km LDM grid. This script checks them
against what pilots actually felt:

  collect  Pull every CONUS PIREP from aviationweather.gov for the window
           NOMADS still holds (today + yesterday UTC), then sample, for
           each turbulence report:
             - GTGN nowcast from the 15-minute file nearest the report;
             - GTG v4 forecast (EDPARM) at LEAD_H hours lead, valid at the
               hour nearest the report.
           Each sample is the max EDR within a horizontal radius (0, 10,
           20, 40 km) and +-1,000 ft of the reported altitude or layer,
           because PIREP positions and altitudes are approximate.
           GRIB files are deleted after sampling. Output:
           data/calib/matches.json.

  analyze  For each category boundary, find the EDR threshold that best
           separates the PIREPs on either side of it (max Peirce skill
           score, POD - POFD, the usual GTG verification score), with a
           bootstrap 90% interval. Writes data/calib/calibration.json and
           out/calibration.png.

Run: uv run calibrate.py collect && uv run calibrate.py analyze
"""
import json
import re
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone

import gtgn_common as gc  # imports pyproj before eccodes (Linux crash)
import eccodes
import numpy as np

from fetch_gtg import curl, parse_idx

CALIB_DIR = gc.DATA_DIR / "calib"
GRIB_TMP = CALIB_DIR / "tmp"
PIREP_URL = "https://aviationweather.gov/api/data/pirep"
GTGN_BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/gtgn/prod"
DAFS_BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod"
CONUS_BBOX = "20,-135,53,-60"
LEAD_H = 2
RADII_KM = [0, 10, 20, 40]
VERT_TOL_FT = 1000
MIN_ALT_FT = 2000  # same cut as route_forecast.NEAR_GROUND_FT

# PIREP intensity -> ordinal. LGT-MOD sits between the two categories.
INTENSITY = {"NEG": 0, "SMTH": 0, "SMTH-LGT": 0.5, "LGT": 1, "LGT-MOD": 1.5,
             "MOD": 2, "MOD-SEV": 2.5, "SEV": 3, "SEV-EXTM": 3.5, "EXTM": 4}

# ICAO weight classes as used by the GTGN guide's Figure 2:
# light < 15,500 lb, medium 15,500-300,000 lb, heavy > 300,000 lb.
MEDIUM_TYPES = re.compile(
    r"^(B73\d|B3[789]M|B7[15]\d|B712|B717|A31\d|A32\d|A2[01]N|BCS\d|E1[79]\d|E[0-9]{2}[LS]|"
    r"E2\d\d|CRJ\d|MD[89]\d|DH8\w|AT[47]\d|CL\d\d|GL\w\w|C68\w|C7\w\w|E55P|F2TH|FA\w\w|G\d{3}|GLF\d)$")
HEAVY_TYPES = re.compile(r"^(B7[4678]\w|B77\w|B78\w|A3[3-9]\w|A35\w|A38\w|MD11|DC10|C17|C5M?)$")


def weight_class(ac):
    ac = (ac or "").upper()
    if HEAVY_TYPES.match(ac):
        return "heavy"
    if MEDIUM_TYPES.match(ac):
        return "medium"
    return "light" if re.match(r"^[A-Z][A-Z0-9]{1,3}$", ac) else "unknown"


# ---------------------------------------------------------------- collect --

def fetch_pireps(start, end):
    """All CONUS PIREPs between start and end, one hourly window at a time
    (the API caps a response at 400 reports)."""
    reports = {}
    t = start + timedelta(hours=1)
    while t <= end + timedelta(hours=1):
        stamp = t.strftime("%Y%m%d_%H%M")
        url = f"{PIREP_URL}?format=json&bbox={CONUS_BBOX}&age=1&date={stamp}"
        rows = json.loads(curl([url], timeout=60))
        if len(rows) >= 400:
            print(f"  warning: {stamp} hit the 400-report cap", file=sys.stderr)
        for r in rows:
            reports[(r["obsTime"], r["rawOb"])] = r
        t += timedelta(hours=1)
    return list(reports.values())


def parse_pirep(r):
    """Return a turbulence observation dict, or None if unusable."""
    inten = (r.get("tbInt1") or "").upper()
    if inten not in INTENSITY:
        return None
    if r.get("tbBas1"):
        lo = r["tbBas1"] * 100
        hi = (r.get("tbTop1") or r["tbBas1"]) * 100
    elif r.get("fltLvl"):
        lo = hi = r["fltLvl"] * 100
    else:
        return None
    lo, hi = sorted((lo, hi))
    if hi < MIN_ALT_FT:
        return None
    return dict(t=r["obsTime"], lat=r["lat"], lon=r["lon"], lo_ft=max(lo, MIN_ALT_FT), hi_ft=hi,
                intensity=inten, level=INTENSITY[inten], ac=r.get("acType"),
                weight=weight_class(r.get("acType")), raw=r["rawOb"])


def levels_for(obs):
    lo, hi = obs["lo_ft"] - VERT_TOL_FT, obs["hi_ft"] + VERT_TOL_FT
    lv = [ft for ft in gc.LEVELS_FT if lo <= ft <= hi]
    if not lv:  # layer between two levels: take the nearest
        lv = [min(gc.LEVELS_FT, key=lambda ft: abs(ft - obs["hi_ft"]))]
    return lv


def read_levels(path, wanted_m):
    """{level_m: 2-D float array with NaN for missing} for the wanted levels."""
    out = {}
    with open(path, "rb") as f:
        while (gid := eccodes.codes_grib_new_from_file(f)) is not None:
            try:
                lvl = eccodes.codes_get(gid, "scaledValueOfFirstFixedSurface")
                if lvl in wanted_m and lvl not in out:
                    nj, ni = eccodes.codes_get(gid, "Nj"), eccodes.codes_get(gid, "Ni")
                    v = eccodes.codes_get_values(gid).reshape(nj, ni)
                    v[v == eccodes.codes_get(gid, "missingValue")] = np.nan
                    out[lvl] = v
            finally:
                eccodes.codes_release(gid)
    return out


_DISKS = {}


def disk(r_cells):
    if r_cells not in _DISKS:
        y, x = np.mgrid[-r_cells:r_cells + 1, -r_cells:r_cells + 1]
        _DISKS[r_cells] = (x * x + y * y) <= r_cells * r_cells
    return _DISKS[r_cells]


def sample(fields, obs_list, proj):
    """Attach {radius_km: max EDR} for each obs from the given level fields."""
    p, x0, y0 = proj
    out = []
    for o in obs_list:
        i, j = gc.latlon_to_ij(np.array([o["lat"]]), np.array([o["lon"]]), p, x0, y0)
        i, j = int(i[0]), int(j[0])
        res = {}
        for rkm in RADII_KM:
            rc = int(round(rkm * 1000 / gc.DX))
            best = np.nan
            for ft in levels_for(o):
                f = fields.get(gc.level_m_for_ft(ft))
                if f is None:
                    continue
                nj, ni = f.shape
                if not (rc <= i < ni - rc and rc <= j < nj - rc):
                    continue
                win = f[j - rc:j + rc + 1, i - rc:i + rc + 1][disk(rc)]
                if np.isfinite(win).any():
                    best = np.nanmax([best, np.nanmax(win)])
            res[str(rkm)] = None if np.isnan(best) else round(float(best), 4)
        out.append(res)
    return out


def download_gtgn(slot):
    name = f"gtgn.t{slot:%H%M}z.3km.grib2"
    url = f"{GTGN_BASE}/gtgn.{slot:%Y%m%d}/{slot:%H}/{name}"
    dest = GRIB_TMP / f"gtgn.{slot:%Y%m%d}.{name}"
    if not dest.exists():
        dest.write_bytes(curl([url], timeout=180))
    return dest


def download_gtg(valid, levels_m):
    """Range-fetch EDPARM at the wanted levels from the forecast made
    LEAD_H hours before `valid`."""
    cyc = valid - timedelta(hours=LEAD_H)
    url = (f"{DAFS_BASE}/dafs.{cyc:%Y%m%d}/dafs.t{cyc:%H}z.gtg.3km.conus."
           f"f{LEAD_H:03d}.grib2")
    dest = GRIB_TMP / f"gtg.{valid:%Y%m%d%H}.f{LEAD_H:03d}.grib2"
    if dest.exists():
        return dest
    rows = parse_idx(curl([url + ".idx"], timeout=30).decode())
    nxt = {rows[k][0]: rows[k + 1][1] for k in range(len(rows) - 1)}
    chunks = []
    for num, off, param, level in rows:
        m = re.match(r"(\d+) m above mean sea level", level)
        if param == "EDPARM" and m and int(m.group(1)) in levels_m:
            chunks.append(curl(["-r", f"{off}-{nxt[num] - 1}", url], timeout=60))
    tmp = dest.with_suffix(".part")
    tmp.write_bytes(b"".join(chunks))
    tmp.rename(dest)
    return dest


def collect():
    now = datetime.now(timezone.utc)
    start = (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    end = now - timedelta(minutes=30)  # let the latest GTGN file land
    GRIB_TMP.mkdir(parents=True, exist_ok=True)

    print(f"PIREPs {start:%Y-%m-%d %H:%M} -> {end:%Y-%m-%d %H:%M} UTC")
    raw = fetch_pireps(start, end)
    (CALIB_DIR / "pireps_raw.json").write_text(json.dumps(raw))
    obs = [o for o in map(parse_pirep, raw) if o and start.timestamp() <= o["t"] <= end.timestamp()]
    print(f"  {len(raw)} reports, {len(obs)} usable turbulence reports >= {MIN_ALT_FT} ft")

    proj = gc.grid_projector()

    def by_slot(minutes):
        groups = defaultdict(list)
        for o in obs:
            t = datetime.fromtimestamp(o["t"], timezone.utc)
            s = t.replace(minute=0, second=0, microsecond=0) + timedelta(
                minutes=minutes * round((t.minute + t.second / 60) / minutes))
            groups[s].append(o)
        return groups

    for product, minutes in (("gtgn", 15), (f"gtg_f{LEAD_H:03d}", 60)):
        groups = by_slot(minutes)
        print(f"{product}: {len(groups)} files to sample")

        def fetch(slot):
            lv = {gc.level_m_for_ft(ft) for o in groups[slot] for ft in levels_for(o)}
            return download_gtgn(slot) if product == "gtgn" else download_gtg(slot, lv)

        done = 0
        with ThreadPoolExecutor(4) as pool:
            futs = {pool.submit(fetch, s): s for s in sorted(groups)}
            for fut in as_completed(futs):
                slot = futs[fut]
                try:
                    path = fut.result()
                except Exception as e:  # noqa: BLE001 - one missing file shouldn't stop the run
                    print(f"  {slot:%Y-%m-%d %H:%M}: skipped ({e})", file=sys.stderr)
                    continue
                lv = {gc.level_m_for_ft(ft) for o in groups[slot] for ft in levels_for(o)}
                fields = read_levels(path, lv)
                for o, s in zip(groups[slot], sample(fields, groups[slot], proj)):
                    o[product] = s
                path.unlink()
                done += 1
                if done % 20 == 0:
                    print(f"  {done}/{len(groups)}")

    (CALIB_DIR / "matches.json").write_text(json.dumps(
        {"start": start.isoformat(), "end": end.isoformat(), "lead_h": LEAD_H,
         "radii_km": RADII_KM, "obs": obs}, indent=1))
    print(f"Saved {CALIB_DIR / 'matches.json'}")


# ---------------------------------------------------------------- analyze --

# Each boundary: which PIREP levels count as "below" and "at or above" it.
# LGT-MOD (1.5) counts as light-or-worse for the light boundary and is left
# out of the moderate boundary, since pilots use it for the grey zone.
BOUNDARIES = {
    "Light": (lambda lv: lv == 0, lambda lv: lv >= 1),
    "Moderate": (lambda lv: lv <= 1, lambda lv: lv >= 2),
    "Severe": (lambda lv: lv <= 2, lambda lv: lv >= 3),
}
GRID = np.round(np.arange(0.05, 0.60, 0.005), 3)


def peirce(neg, pos, thr):
    pod = (pos >= thr).mean()
    pofd = (neg >= thr).mean()
    return pod - pofd, pod, pofd


def best_threshold(neg, pos):
    scores = np.array([peirce(neg, pos, t)[0] for t in GRID])
    return float(GRID[int(np.argmax(scores))]), scores


def fit(obs, product, radius, rng, n_boot=500):
    edr = np.array([np.nan if (v := (o.get(product) or {}).get(str(radius))) is None else v
                    for o in obs], float)
    lv = np.array([o["level"] for o in obs])
    ok = np.isfinite(edr)
    edr, lv = edr[ok], lv[ok]
    out = {}
    for name, (is_neg, is_pos) in BOUNDARIES.items():
        neg, pos = edr[is_neg(lv)], edr[is_pos(lv)]
        if len(pos) < 10 or len(neg) < 10:
            out[name] = dict(n_neg=int(len(neg)), n_pos=int(len(pos)), threshold=None)
            continue
        thr, scores = best_threshold(neg, pos)
        boots = [best_threshold(rng.choice(neg, len(neg)), rng.choice(pos, len(pos)))[0]
                 for _ in range(n_boot)]
        old = OLD[name]
        out[name] = dict(
            n_neg=int(len(neg)), n_pos=int(len(pos)), threshold=thr,
            ci90=[float(np.percentile(boots, 5)), float(np.percentile(boots, 95))],
            pss=round(float(scores.max()), 3),
            pod_pofd_at_best=[round(float(x), 3) for x in peirce(neg, pos, thr)[1:]],
            pss_at_old=round(float(peirce(neg, pos, old)[0]), 3),
            pod_pofd_at_old=[round(float(x), 3) for x in peirce(neg, pos, old)[1:]],
            scores=[round(float(s), 4) for s in scores])
    by_level = defaultdict(list)
    for e, l in zip(edr, lv):
        by_level[float(l)].append(float(e))
    out["median_by_level"] = {k: round(float(np.median(v)), 3) for k, v in sorted(by_level.items())}
    out["n_by_level"] = {k: len(v) for k, v in sorted(by_level.items())}
    return out


OLD = {"Light": 0.15, "Moderate": 0.20, "Severe": 0.44}


def analyze():
    m = json.loads((CALIB_DIR / "matches.json").read_text())
    obs = m["obs"]
    rng = np.random.default_rng(0)
    from collections import Counter
    print(f"Window {m['start']} -> {m['end']}: {len(obs)} turbulence PIREPs")
    print("  by intensity:", dict(Counter(o["intensity"] for o in obs)))
    print("  by weight class:", dict(Counter(o["weight"] for o in obs)))

    result = {"window": [m["start"], m["end"]], "n_obs": len(obs), "grid": GRID.tolist(),
              "old": OLD, "fits": {}}
    for product in ("gtgn", f"gtg_f{m['lead_h']:03d}"):
        for subset in ("all", "medium"):
            sel = [o for o in obs if subset == "all" or o["weight"] == "medium"]
            for radius in m["radii_km"]:
                key = f"{product}/{subset}/r{radius}"
                f = fit(sel, product, radius, rng)
                result["fits"][key] = f
                line = "  ".join(
                    f"{b}: {f[b]['threshold']} [{f[b]['ci90'][0]:.3f}-{f[b]['ci90'][1]:.3f}] "
                    f"PSS {f[b]['pss']} (old {f[b]['pss_at_old']}) n={f[b]['n_neg']}/{f[b]['n_pos']}"
                    if f[b]["threshold"] is not None else f"{b}: n={f[b]['n_neg']}/{f[b]['n_pos']} (too few)"
                    for b in BOUNDARIES)
                print(f"{key:24s} {line}")
                print(f"{'':24s} median EDR by PIREP level: {f['median_by_level']}")
    (CALIB_DIR / "calibration.json").write_text(json.dumps(result, indent=1))
    print(f"Saved {CALIB_DIR / 'calibration.json'}")
    plot(obs, result, f"gtg_f{m['lead_h']:03d}")


def plot(obs, result, gtg_key):
    """out/calibration.png: EDR by PIREP intensity, and skill vs threshold."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from route import MEDIUM_THRESHOLDS as NEW

    products = [("gtgn", "GTGN nowcast"), (gtg_key, f"GTG forecast, {gtg_key[-3:].lstrip('f0')}h lead")]
    classes = [(0, "NEG"), (1, "LGT"), (1.5, "LGT-MOD"), (2, "MOD"), (2.5, "MOD-SEV+")]
    fig, axes = plt.subplots(2, 2, figsize=(12, 8.5))
    for col, (prod, label) in enumerate(products):
        ax = axes[0, col]
        data = [[o[prod]["0"] for o in obs if o.get(prod) and o[prod]["0"] is not None
                 and (o["level"] == lv if lv < 2.5 else o["level"] >= 2.5)] for lv, _ in classes]
        ax.boxplot(data, showfliers=False, whis=(10, 90))
        ax.set_xticks(range(1, len(classes) + 1),
                      [f"{name}\nn={len(d)}" for (_, name), d in zip(classes, data)])
        for name, color in (("Light", "#c9b400"), ("Moderate", "#ff8c00")):
            ax.axhline(OLD[name], color=color, linestyle=":", linewidth=1.2)
            ax.axhline(NEW[name], color=color, linewidth=1.5, label=f"{name}: {OLD[name]} -> {NEW[name]}")
        ax.set_ylim(0, 0.32)
        ax.set_title(f"{label}: EDR at the PIREP vs what the pilot reported")
        ax.set_ylabel("EDR (m$^{2/3}$ s$^{-1}$)")
        ax.legend(fontsize=8, loc="upper left", title="dotted = guide, solid = new", title_fontsize=8)

        ax = axes[1, col]
        fit = result["fits"][f"{prod}/all/r0"]
        for name, color in (("Light", "#c9b400"), ("Moderate", "#ff8c00")):
            ax.plot(GRID, fit[name]["scores"], color=color, label=f"{name} boundary")
            ax.axvline(OLD[name], color=color, linestyle=":", linewidth=1.2)
            ax.axvline(NEW[name], color=color, linewidth=1.5)
        ax.set_xlim(0.05, 0.35)
        ax.set_xlabel("EDR threshold")
        ax.set_ylabel("Peirce skill score (POD - POFD)")
        ax.set_title(f"{label}: skill vs threshold")
        ax.legend(fontsize=8)
        ax.grid(alpha=0.3)
    n = len(obs)
    fig.suptitle(f"Category calibration against {n} PIREPs, {result['window'][0][:16]} to "
                 f"{result['window'][1][:16]} UTC (point sample, +-1,000 ft)")
    fig.tight_layout()
    out = gc.DATA_DIR.parent / "out" / "calibration.png"
    out.parent.mkdir(exist_ok=True)
    fig.savefig(out, dpi=120)
    print(f"Saved {out}")


if __name__ == "__main__":
    {"collect": collect, "analyze": analyze}[sys.argv[1] if len(sys.argv) > 1 else "analyze"]()
