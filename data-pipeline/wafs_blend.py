#!/usr/bin/env python3
"""
WAFS turbulence for the global layer: the official World Area Forecast System EDR, as the Aviation Weather Center
publishes it for its public WAFS viewer (https://aviationweather.gov/wafs/), blended with the GFS-derived TI1 layer
(gfs_turbulence.py).

The raw WAFS GRIB2 needs an approved account (NOMADS wafs/ is 403), but AWC serves each field as a public 900x600
Mercator PNG (lat +-75.94, lon +-180, ~0.4 deg) with a 0-45 colour ramp:

    /data/products/wafs/YYYYMMDD/HH/YYYYMMDD_HH_Fhh_wafs_<hPa>_edr_m.png    hh = 06..48 every 3 h

at 8 levels (595, 506, 393, 344, 301, 250, 197, 148 hPa = FL140, 180, 240, 270, 300, 340, 390, 450).

    uv run wafs_blend.py collect   # WAFS F06 PNGs for the gfs_turbulence.py pairs (same valid times)
    uv run wafs_blend.py fit       # fit and score; writes backend/.../turbulence/wafs-blend.json

Method:
  1. Colour -> ramp step (0 = clear) -> EDR by quantile matching against GTG over CONUS, pooled over levels.
  2. Per GTG altitude from 15,000 ft up: WAFS EDR interpolated between its flight levels, mixed with the TI1 EDR
     (WEIGHT on WAFS), then quantile-mapped to GTG so turbol's thresholds keep GTG's category frequencies.
Below FL140 (no WAFS) the TI1 layer stays as it is.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image

import gfs_turbulence as gt
from fetch_gtg import curl

WAFS_DIR = gt.DATA / "wafs"
BACKEND_TABLE = Path(__file__).parent.parent / "backend/src/main/resources/turbulence/wafs-blend.json"
URL = "https://aviationweather.gov/data/products/wafs"
LEVELS_HPA = [595, 506, 393, 344, 301, 250, 197, 148]
LEVELS_FL = [140, 180, 240, 270, 300, 340, 390, 450]
MAX_LAT = 75.940536
WEIGHT = 0.8
MIN_FT = 15000
# The viewer's colour ramp, clear air first: light green -> yellow -> orange -> red -> dark red.
RAMP = ([(204, 255, 153), (204, 255, 102), (204, 255, 51), (204, 255, 0), (214, 244, 0), (224, 234, 0), (234, 224, 0),
         (244, 214, 0), (255, 204, 0)]
        + [(255, g, 0) for g in (193, 183, 173, 163, 153, 142, 132, 122, 112, 102, 91, 81, 71, 61, 50, 40, 30, 20, 10, 0)]
        + [(r, 0, 0) for r in (244, 234, 224, 214, 204, 193, 183, 173, 163, 153, 142, 132, 122, 112, 102)])


def png_path(tag, hpa):
    return WAFS_DIR / f"{tag}_{hpa}.png"


def collect():
    WAFS_DIR.mkdir(parents=True, exist_ok=True)
    for tag in gt.pairs():
        day, hh = tag[:8], tag[8:]
        for hpa in LEVELS_HPA:
            path = png_path(tag, hpa)
            if not path.exists():
                path.write_bytes(curl([f"{URL}/{day}/{hh}/{day}_{hh}_F{gt.LEAD:02d}_wafs_{hpa}_edr_m.png"], timeout=60))
        print(tag, "ok")


def steps(path):
    """Ramp step (0 = clear, 1..len(RAMP)) per pixel; colours off the ramp take the nearest ramp colour."""
    im = Image.open(path)
    palette = np.array(im.getpalette()[: 3 * 256]).reshape(-1, 3)
    ramp = np.array(RAMP)
    lut = 1 + np.argmin(((palette[:, None, :] - ramp[None, :, :]) ** 2).sum(-1), axis=1)
    lut[np.all(palette == 255, axis=1)] = 0
    transparent = im.info.get("transparency")
    if isinstance(transparent, int):
        lut[transparent] = 0
    return lut[np.array(im)]


def sample(img, lat, lon):
    """Nearest WAFS pixel at each (lat, lon) grid point; NaN poleward of the image."""
    h, w = img.shape
    lon2, lat2 = np.meshgrid(np.where(lon > 180, lon - 360, lon), lat)
    x = np.clip(np.floor((lon2 + 180) / 360 * w).astype(int), 0, w - 1)
    inside = np.abs(lat2) < MAX_LAT
    merc = np.log(np.tan(np.pi / 4 + np.radians(np.where(inside, lat2, 0)) / 2))
    top = np.log(np.tan(np.pi / 4 + np.radians(MAX_LAT) / 2))
    y = np.clip(np.floor((top - merc) / (2 * top) * h).astype(int), 0, h - 1)
    return np.where(inside, img[y, x], np.nan)


def wafs_steps(tag, lat, lon):
    return np.stack([sample(steps(png_path(tag, hpa)), lat, lon) for hpa in LEVELS_HPA])


def interp_fl(by_level, ft):
    fl = min(max(ft / 100, LEVELS_FL[0]), LEVELS_FL[-1])
    k = max(i for i, v in enumerate(LEVELS_FL) if v <= fl) if fl < LEVELS_FL[-1] else len(LEVELS_FL) - 2
    t = (fl - LEVELS_FL[k]) / (LEVELS_FL[k + 1] - LEVELS_FL[k])
    return (1 - t) * by_level[k] + t * by_level[k + 1]


def blend_levels():
    return [n for n, ft in enumerate(gt.TARGET_FT) if ft >= MIN_FT]


def step_table(tags):
    """EDR per ramp step by quantile matching (each step's mid-quantile), pooled over every blended level."""
    s, g = [], []
    for tag in tags:
        lat, lon, _, gtg = gt.load(tag)
        w = wafs_steps(tag, lat, lon)
        for n in blend_levels():
            nearest = w[int(np.argmin([abs(fl * 100 - gt.TARGET_FT[n]) for fl in LEVELS_FL]))]
            ok = np.isfinite(gtg[n]) & np.isfinite(nearest)
            s.append(nearest[ok].astype(int))
            g.append(gtg[n][ok])
    s, g = np.concatenate(s), np.concatenate(g)
    edr = []
    for r in range(len(RAMP) + 1):
        if np.sum(s == r) >= 30:
            q = (np.mean(s < r) + np.mean(s <= r)) / 2
            edr.append(float(np.quantile(g, q)))
        else:  # rare: extend by the mean of the last three increments
            d = np.mean(np.diff(edr[-4:]))
            edr.append(min(1.0, edr[-1] + d))
    return np.maximum.accumulate(np.array(edr))


def mixed(tag, steps_edr, ti1_tables):
    """Per blended level: (WEIGHT * WAFS + (1 - WEIGHT) * TI1) EDR and GTG EDR."""
    lat, lon, idx, gtg = gt.load(tag)
    w = steps_edr[np.nan_to_num(wafs_steps(tag, lat, lon), nan=0).astype(int)]
    ti1 = gt.gfs_edr(idx, ti1_tables)
    out = {}
    for n in blend_levels():
        out[n] = (WEIGHT * interp_fl(w, gt.TARGET_FT[n]) + (1 - WEIGHT) * ti1[n], gtg[n], ti1[n])
    return out


def blend_tables(tags, steps_edr, ti1_tables):
    xs, ys = {n: [] for n in blend_levels()}, {n: [] for n in blend_levels()}
    for tag in tags:
        for n, (m, g, _) in mixed(tag, steps_edr, ti1_tables).items():
            ok = np.isfinite(g)
            xs[n].append(m[ok])
            ys[n].append(g[ok])
    return {n: (np.quantile(np.concatenate(xs[n]), gt.KNOTS), np.quantile(np.concatenate(ys[n]), gt.KNOTS))
            for n in blend_levels()}


def score(tags, steps_edr, ti1_tables, tables):
    """Same FL200+ scores as gfs_turbulence.score, for TI1 alone and for the blend."""
    rows = {"ti1": [], "wafs+ti1": []}
    for tag in tags:
        res = mixed(tag, steps_edr, ti1_tables)
        sel = [n for n in res if gt.band(gt.TARGET_FT[n]) == "upper"]
        g = np.stack([res[n][1] for n in sel])
        ok = np.isfinite(g)
        for name, est in [("ti1", np.stack([res[n][2] for n in sel])),
                          ("wafs+ti1", np.stack([np.interp(res[n][0], *tables[n]) for n in sel]))]:
            gp, ep = g[ok], est[ok]
            gm, em = gt.block_max(g)[ok], gt.block_max(est)[ok]
            rows[name].append(dict(
                r_log=float(np.corrcoef(np.log(gp), np.log(np.maximum(ep, 1e-3)))[0, 1]),
                pss_light=float(gt.pss(ep >= gt.LIGHT, gp >= gt.LIGHT)[0]),
                pss_moderate=float(gt.pss(ep >= gt.MODERATE, gp >= gt.MODERATE)[0]),
                pss_light_75km=float(gt.pss(em >= gt.LIGHT, gm >= gt.LIGHT)[0]),
                light_pct_gtg=float(100 * np.mean(gp >= gt.LIGHT)),
                light_pct=float(100 * np.mean(ep >= gt.LIGHT)),
            ))
    return {k: {m: float(np.mean([r[m] for r in v])) for m in v[0]} for k, v in rows.items()}


def fit():
    tags = gt.pairs()
    ti1_tables = gt.load_tables()
    train, test = tags[: len(tags) // 2], tags[len(tags) // 2:]
    steps_edr = step_table(train)
    scores = score(test, steps_edr, ti1_tables, blend_tables(train, steps_edr, ti1_tables))
    print("train", train, "test", test)
    for name, s in scores.items():
        print(f"{name:9}", "  ".join(f"{k} {v:.3f}" for k, v in s.items()))
    steps_edr = step_table(tags)
    tables = blend_tables(tags, steps_edr, ti1_tables)
    BACKEND_TABLE.write_text(json.dumps(dict(
        source=f"data-pipeline/wafs_blend.py fit, WAFS F{gt.LEAD:02d} + GFS TI1 vs GTG f{gt.LEAD:03d}, "
               f"cycles {tags[0]}-{tags[-1]}",
        testScores=scores,
        levelsHpa=LEVELS_HPA, levelsFl=LEVELS_FL, maxLat=MAX_LAT, weight=WEIGHT,
        ramp=[list(c) for c in RAMP], stepEdr=[round(float(v), 4) for v in steps_edr],
        levelsFt=[gt.TARGET_FT[n] for n in blend_levels()],
        mixed=[[float(f"{v:.4e}") for v in tables[n][0]] for n in blend_levels()],
        edr=[[round(float(v), 4) for v in tables[n][1]] for n in blend_levels()],
    ), indent=1) + "\n")
    print("wrote", BACKEND_TABLE)


if __name__ == "__main__":
    {"collect": collect, "fit": fit}[sys.argv[1] if len(sys.argv) > 1 else "fit"]()
