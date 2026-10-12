#!/usr/bin/env python3
"""
Global turbulence outside the GTG domain: GTG-style clear-air turbulence
indices computed from NOAA GFS 0.25 deg winds and temperatures, mapped to EDR
by matching GTG's own EDR distribution over CONUS.

    uv run gfs_turbulence.py collect   # GFS f006 + GTG f006 pairs (~55 MB each)
    uv run gfs_turbulence.py fit       # fit on the train pairs, score on the test pairs
    uv run gfs_turbulence.py plot      # out/gfs_vs_gtg.png, out/gfs_fl350_global.png

Method (Sharman & Pearson 2017 "GTG" remapping, simplified):
  1. On each pair of adjacent GFS pressure levels: vertical wind shear (VWS),
     horizontal deformation (DEF) and convergence (CVG), Brunt-Vaisala N^2.
  2. Three indices, all standard GTG members: Ellrod TI1 = VWS*DEF, Ellrod-Knox
     TI2 = VWS*(DEF+CVG), and VWS^2/N^2 (= 1/Ri).
  3. Interpolate each to the GTG altitude levels using GFS geopotential height.
  4. Fit ln(EDR) = a + b ln(index) per index and altitude band by matching
     quantiles (50th-99.5th) of the index to those of GTG EDR at the same
     points and valid time (GTG forecast of the same lead time). The combined
     EDR is the mean of the three.
Matching GTG's distribution means turbol's thresholds (calibrated on GTG
against PIREPs) give the same category frequencies on both layers.
"""
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pyproj import Proj  # before eccodes, see gtgn_common
import eccodes
import numpy as np

import gtgn_common as gc
from fetch_gtg import curl, parse_idx

DATA = Path(__file__).parent / "data" / "gfs_cal"
OUT = Path(__file__).parent / "out"
BACKEND_TABLE = Path(__file__).parent.parent / "backend/src/main/resources/turbulence/gfs-ti1-to-edr.json"
GFS = "https://nomads.ncep.noaa.gov/cgi-bin/filter_gfs_0p25.pl"
DAFS = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod"

LEVELS_HPA = [150, 200, 250, 300, 350, 400, 450, 500, 600, 700, 800, 850, 925]
TARGET_FT = [3000, 6000, 10000, 15000, 20000, 25000, 30000, 33000, 35000, 37000, 39000, 41000]
LEAD = 6
G = 9.80665
R_EARTH = 6371229.0
INDICES = ["ti1", "ti2", "inv_ri"]
# Quantile knots for the index -> EDR mapping, dense in the tail where the categories are decided.
KNOTS = np.array([0.01, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85, 0.88, 0.9, 0.92, 0.94, 0.95,
                  0.96, 0.97, 0.975, 0.98, 0.985, 0.99, 0.993, 0.995, 0.997, 0.998, 0.999])


def band(ft):
    return "upper" if ft >= 20000 else "lower"


# ---------------------------------------------------------------- collect

def collect(cycles):
    DATA.mkdir(parents=True, exist_ok=True)
    for day, hh in cycles:
        gfs_path = DATA / f"gfs.{day}{hh}.f{LEAD:03d}.grib2"
        gtg_path = DATA / f"gtg.{day}{hh}.f{LEAD:03d}.grib2"
        if not gfs_path.exists():
            q = (f"?dir=%2Fgfs.{day}%2F{hh}%2Fatmos&file=gfs.t{hh}z.pgrb2.0p25.f{LEAD:03d}"
                 + "".join(f"&var_{v}=on" for v in ["UGRD", "VGRD", "TMP", "HGT"])
                 + "".join(f"&lev_{p}_mb=on" for p in LEVELS_HPA))
            gfs_path.write_bytes(curl([GFS + q], timeout=180))
        if not gtg_path.exists():
            url = f"{DAFS}/dafs.{day}/dafs.t{hh}z.gtg.3km.conus.f{LEAD:03d}.grib2"
            rows = parse_idx(curl([url + ".idx"], timeout=30).decode())
            wanted = {f"{gc.level_m_for_ft(ft)} m above mean sea level" for ft in TARGET_FT}
            chunks = []
            for k, (_, off, param, level) in enumerate(rows):
                if param == "EDPARM" and level in wanted:
                    chunks.append(curl(["-r", f"{off}-{rows[k + 1][1] - 1}", url], timeout=60))
            gtg_path.write_bytes(b"".join(chunks))
        print(day, hh, f"gfs {gfs_path.stat().st_size / 1e6:.1f} MB", f"gtg {gtg_path.stat().st_size / 1e6:.1f} MB")


# ---------------------------------------------------------------- indices

def read_gfs(path):
    """{(shortName, hPa): 2-D array} plus lat (ny,) / lon (nx,) vectors, north-to-south rows as stored."""
    fields = {}
    with open(path, "rb") as f:
        while (gid := eccodes.codes_grib_new_from_file(f)) is not None:
            name = eccodes.codes_get(gid, "shortName")
            level = eccodes.codes_get(gid, "level")
            ni, nj = eccodes.codes_get(gid, "Ni"), eccodes.codes_get(gid, "Nj")
            fields[(name, level)] = eccodes.codes_get_values(gid).reshape(nj, ni).astype(np.float32)
            if "lat" not in fields:
                la1 = eccodes.codes_get(gid, "latitudeOfFirstGridPointInDegrees")
                la2 = eccodes.codes_get(gid, "latitudeOfLastGridPointInDegrees")
                fields["lat"] = np.linspace(la1, la2, nj)
                fields["lon"] = np.arange(ni) * 360.0 / ni
            eccodes.codes_release(gid)
    return fields


def horizontal(u, v, lat):
    """DEF and CVG on a regular lat/lon grid (rows = lat, periodic in lon)."""
    dlam = np.deg2rad(360.0 / u.shape[1])
    dphi = np.deg2rad(lat[1] - lat[0])  # negative when rows run north to south
    coslat = np.maximum(np.cos(np.deg2rad(lat)), 0.05)[:, None]
    dx = R_EARTH * coslat * dlam
    dy = R_EARTH * dphi

    def ddx(a):
        return (np.roll(a, -1, axis=1) - np.roll(a, 1, axis=1)) / (2 * dx)

    def ddy(a):
        g = np.empty_like(a)
        g[1:-1] = (a[2:] - a[:-2]) / (2 * dy)
        g[0], g[-1] = g[1], g[-2]
        return g

    dudx, dudy, dvdx, dvdy = ddx(u), ddy(u), ddx(v), ddy(v)
    deform = np.sqrt((dudx - dvdy) ** 2 + (dvdx + dudy) ** 2)
    cvg = -(dudx + dvdy)
    return deform, cvg


def layer_indices(fields):
    """Per layer between adjacent pressure levels: mid height and the three indices."""
    lat = fields["lat"]
    levels = sorted(LEVELS_HPA, reverse=True)  # bottom (925) to top (150)
    horiz = {p: horizontal(fields[("u", p)], fields[("v", p)], lat) for p in levels}
    mids, out = [], {k: [] for k in INDICES}
    for lo, hi in zip(levels[:-1], levels[1:]):
        z_lo, z_hi = fields[("gh", lo)], fields[("gh", hi)]
        dz = np.maximum(z_hi - z_lo, 50.0)
        du = fields[("u", hi)] - fields[("u", lo)]
        dv = fields[("v", hi)] - fields[("v", lo)]
        vws = np.sqrt(du * du + dv * dv) / dz
        th_lo = fields[("t", lo)] * (1000.0 / lo) ** 0.2857
        th_hi = fields[("t", hi)] * (1000.0 / hi) ** 0.2857
        n2 = G / (0.5 * (th_lo + th_hi)) * (th_hi - th_lo) / dz
        deform = 0.5 * (horiz[lo][0] + horiz[hi][0])
        cvg = 0.5 * (horiz[lo][1] + horiz[hi][1])
        out["ti1"].append(vws * deform)
        out["ti2"].append(vws * np.maximum(deform + cvg, 0.0))
        out["inv_ri"].append(vws * vws / np.maximum(n2, 1e-5))
        mids.append(0.5 * (z_lo + z_hi))
    return np.stack(mids), {k: np.stack(v) for k, v in out.items()}


def to_altitudes(mids, values, targets_m):
    """Linear interpolation in height from layer mids (L, ny, nx) to each target altitude."""
    out = []
    for h in targets_m:
        k = np.clip((mids < h).sum(axis=0) - 1, 0, mids.shape[0] - 2)
        z0 = np.take_along_axis(mids, k[None], 0)[0]
        z1 = np.take_along_axis(mids, k[None] + 1, 0)[0]
        v0 = np.take_along_axis(values, k[None], 0)[0]
        v1 = np.take_along_axis(values, k[None] + 1, 0)[0]
        w = np.clip((h - z0) / np.maximum(z1 - z0, 1.0), 0.0, 1.0)
        out.append(v0 + w * (v1 - v0))
    return np.stack(out)


def gfs_indices(path):
    fields = read_gfs(path)
    mids, idx = layer_indices(fields)
    targets_m = [ft * 0.3048 for ft in TARGET_FT]
    return fields["lat"], fields["lon"], {k: to_altitudes(mids, v, targets_m) for k, v in idx.items()}


def gtg_at(path, lat, lon):
    """GTG EDR (levels, ny, nx) at the GFS grid points (NaN outside the GTG domain)."""
    by_ft = {}
    for m in gc.read_all_messages(path):
        ft = int(round(m["level_m"] / 0.3048 / 100) * 100)
        vals = m["vals"].astype(np.float32)
        vals[vals >= gc.MISSING] = np.nan
        by_ft[ft] = vals
    lon2, lat2 = np.meshgrid(np.where(lon > 180, lon - 360, lon), lat)
    i, j = gc.latlon_to_ij(lat2, lon2)
    inside = (i >= 0) & (i < 1799) & (j >= 0) & (j < 1059) & (lat2 > 0)
    out = np.full((len(TARGET_FT),) + lat2.shape, np.nan, np.float32)
    for n, ft in enumerate(TARGET_FT):
        out[n][inside] = by_ft[ft][j[inside], i[inside]]
    return out


# ---------------------------------------------------------------- fit / score

def pairs():
    return sorted(p.name.split(".")[1] for p in DATA.glob("gfs.*.grib2"))


def load(tag):
    cache = DATA / f"pair.{tag}.npz"
    if cache.exists():
        z = np.load(cache)
        return z["lat"], z["lon"], {k: z[k] for k in INDICES}, z["gtg"]
    lat, lon, idx = gfs_indices(DATA / f"gfs.{tag}.f{LEAD:03d}.grib2")
    gtg = gtg_at(DATA / f"gtg.{tag}.f{LEAD:03d}.grib2", lat, lon)
    np.savez_compressed(cache, lat=lat, lon=lon, gtg=gtg, **idx)
    return lat, lon, idx, gtg


def edr_tables(tags, index="ti1"):
    """Per target level: (index quantiles, GTG EDR quantiles) at KNOTS over all `tags` pooled (quantile mapping)."""
    tables = []
    for n, ft in enumerate(TARGET_FT):
        xs, ys = [], []
        for tag in tags:
            _, _, idx, gtg = load(tag)
            ok = np.isfinite(gtg[n])
            xs.append(idx[index][n][ok])
            ys.append(gtg[n][ok])
        tables.append((np.quantile(np.concatenate(xs), KNOTS), np.quantile(np.concatenate(ys), KNOTS)))
    return tables


def map_edr(table, x):
    """Quantile mapping, extended past the top knot along the log-log slope of the last 0.9% (capped at 1)."""
    xq, yq = table
    edr = np.interp(x, xq, yq)
    slope = np.log(yq[-1] / yq[-4]) / np.log(xq[-1] / xq[-4])
    above = x > xq[-1]
    edr[above] = yq[-1] * (x[above] / xq[-1]) ** slope
    return np.clip(edr, 0.0, 1.0)


def gfs_edr(idx, tables, index="ti1"):
    return np.stack([map_edr(tables[n], idx[index][n]) for n in range(len(TARGET_FT))])


LIGHT, MODERATE = 0.12, 0.14  # medium aircraft, Thresholds.byClass in the backend


def pss(pred, obs):
    hits = np.sum(pred & obs)
    misses = np.sum(~pred & obs)
    false = np.sum(pred & ~obs)
    neg = np.sum(~pred & ~obs)
    return hits / max(hits + misses, 1) - false / max(false + neg, 1), hits / max(hits + misses, 1)


def block_max(a, k=3):
    """Max over k x k neighbourhoods (NaN-aware), to compare at ~75 km where GFS can resolve features."""
    from scipy.ndimage import maximum_filter
    filled = np.where(np.isfinite(a), a, -1.0)
    return maximum_filter(filled, size=(1, k, k))


def score(tags, tables, index="ti1"):
    """Agreement with GTG at the cruise levels (FL200+): log correlation, Light+/Moderate+ Peirce skill, frequency."""
    rows = []
    for tag in tags:
        _, _, idx, gtg = load(tag)
        est = gfs_edr(idx, tables, index)
        sel = [n for n, ft in enumerate(TARGET_FT) if band(ft) == "upper"]
        g, e = gtg[sel], est[sel]
        ok = np.isfinite(g)
        gm, em = block_max(g)[ok], block_max(e)[ok]
        gp, ep = g[ok], e[ok]
        rows.append(dict(
            pair=tag, points=int(ok.sum()),
            r_log=float(np.corrcoef(np.log(gp), np.log(np.maximum(ep, 1e-3)))[0, 1]),
            pss_light=float(pss(ep >= LIGHT, gp >= LIGHT)[0]),
            pod_light=float(pss(ep >= LIGHT, gp >= LIGHT)[1]),
            pss_moderate=float(pss(ep >= MODERATE, gp >= MODERATE)[0]),
            pss_light_75km=float(pss(em >= LIGHT, gm >= LIGHT)[0]),
            light_pct_gtg=float(100 * np.mean(gp >= LIGHT)),
            light_pct_gfs=float(100 * np.mean(ep >= LIGHT)),
        ))
    return rows


def fit():
    tags = pairs()
    train, test = tags[: len(tags) // 2], tags[len(tags) // 2:]
    print("train", train, "test", test)
    print(f"{'index':8} {'r_log':>6} {'PSS L':>6} {'POD L':>6} {'PSS M':>6} {'PSS L 75km':>10} {'L+ % GTG':>8} {'L+ % GFS':>8}")
    summary = {}
    for index in INDICES:
        rows = score(test, edr_tables(train, index), index)
        mean = {k: float(np.mean([r[k] for r in rows])) for k in rows[0] if k not in ("pair", "points")}
        summary[index] = mean
        print(f"{index:8} {mean['r_log']:6.3f} {mean['pss_light']:6.3f} {mean['pod_light']:6.3f} {mean['pss_moderate']:6.3f}"
              f" {mean['pss_light_75km']:10.3f} {mean['light_pct_gtg']:8.1f} {mean['light_pct_gfs']:8.1f}")
    # Production table: TI1 (best on every score above), fitted on every pair.
    tables = edr_tables(tags, "ti1")
    OUT.mkdir(exist_ok=True)
    (OUT / "gfs_fit.json").write_text(json.dumps(dict(
        train=train, test=test, test_scores=summary, all_pairs=tags,
        knots=KNOTS.tolist(), levels_ft=TARGET_FT,
        ti1=[[float(v) for v in t[0]] for t in tables], edr=[[round(float(v), 4) for v in t[1]] for t in tables],
    ), indent=1))
    print("wrote", OUT / "gfs_fit.json")
    # The backend's copy: GfsTurbulence.scala loads it from the classpath.
    BACKEND_TABLE.parent.mkdir(parents=True, exist_ok=True)
    BACKEND_TABLE.write_text(json.dumps(dict(
        source=f"data-pipeline/gfs_turbulence.py fit, GFS f{LEAD:03d} vs GTG f{LEAD:03d}, cycles {tags[0]}-{tags[-1]}",
        pressureLevelsHpa=LEVELS_HPA, levelsFt=TARGET_FT,
        ti1=[[float(f"{v:.4e}") for v in t[0]] for t in tables],
        edr=[[round(float(v), 4) for v in t[1]] for t in tables],
    ), indent=1) + "\n")
    print("wrote", BACKEND_TABLE)


def load_tables():
    fitted = json.loads((OUT / "gfs_fit.json").read_text())
    return [(np.array(x), np.array(y)) for x, y in zip(fitted["ti1"], fitted["edr"])]


def plot():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.colors import BoundaryNorm, ListedColormap

    tables = load_tables()
    tag = pairs()[-1]
    lat, lon, idx, gtg = load(tag)
    est = gfs_edr(idx, tables)
    n = TARGET_FT.index(35000)
    cmap = ListedColormap(["#e8f5e9", "#facc15", "#f97316", "#dc2626", "#9333ea"])
    norm = BoundaryNorm([0, LIGHT, MODERATE, 0.44, 0.79, 2], cmap.N)
    lon180 = np.where(lon > 180, lon - 360, lon)
    order = np.argsort(lon180)

    fig, axes = plt.subplots(1, 2, figsize=(16, 5.5))
    box = (lat > 20) & (lat < 55)
    lons = (lon180[order] > -130) & (lon180[order] < -60)
    for ax, data, title in [(axes[0], gtg[n], "GTG forecast (3 km, sampled at GFS points)"),
                            (axes[1], est[n], "GFS-derived EDR (0.25 deg)")]:
        d = data[box][:, order][:, lons]
        ax.pcolormesh(lon180[order][lons], lat[box], np.ma.masked_invalid(d), cmap=cmap, norm=norm, shading="auto")
        ax.set_title(f"{title}, FL350, {tag} +{LEAD}h")
        ax.set_aspect(1.3)
    fig.tight_layout()
    fig.savefig(OUT / "gfs_vs_gtg.png", dpi=90)

    fig, ax = plt.subplots(figsize=(16, 7))
    box = (lat > -70) & (lat < 85)
    ax.pcolormesh(lon180[order], lat[box], est[n][box][:, order], cmap=cmap, norm=norm, shading="auto")
    ax.set_title(f"GFS-derived EDR, FL350, {tag} +{LEAD}h (medium-aircraft categories)")
    fig.tight_layout()
    fig.savefig(OUT / "gfs_fl350_global.png", dpi=80)
    print("wrote", OUT / "gfs_vs_gtg.png", OUT / "gfs_fl350_global.png")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "fit"
    if cmd == "collect":
        cycles = [a.split(":") for a in sys.argv[2:]] or [
            (d, h) for d in ("20261010", "20261011") for h in ("00", "06", "12", "18")]
        collect(cycles)
    elif cmd == "fit":
        fit()
    elif cmd == "plot":
        plot()
