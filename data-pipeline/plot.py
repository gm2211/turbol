#!/usr/bin/env python3
"""Render out/fl350_map.png (EDR at FL350 over CONUS with JFK-LAX route)
and out/route_profile.png (EDR vs distance along the route, with medium-
aircraft category threshold lines from route.py)."""
import json

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

import gtgn_common as gc
from route import CRUISE_FT, JFK, LAX, MEDIUM_THRESHOLDS

OUT_DIR = gc.DATA_DIR.parent / "out"


def plot_map(path):
    idx = gc.message_index_for_level_ft(CRUISE_FT)
    vals, lats, lons, level_m, missing = gc.read_message(path, idx)
    plot_vals = np.where(vals == missing, np.nan, vals)

    samples = np.load(gc.DATA_DIR / "route_samples.npz")

    try:
        import cartopy.crs as ccrs
        import cartopy.feature as cfeature
        fig = plt.figure(figsize=(10, 6.5))
        ax = fig.add_subplot(1, 1, 1, projection=ccrs.PlateCarree())
        ax.set_extent([-127, -66, 20, 53], crs=ccrs.PlateCarree())
        ax.set_facecolor("white")
        ax.add_feature(cfeature.COASTLINE, linewidth=0.6, color="0.3")
        ax.add_feature(cfeature.BORDERS, linewidth=0.5, color="0.3")
        ax.add_feature(cfeature.STATES.with_scale("50m"), linewidth=0.3,
                        edgecolor="0.6", facecolor="none")
        mesh = ax.pcolormesh(lons, lats, plot_vals, cmap="turbo", vmin=0, vmax=0.4,
                              shading="auto", transform=ccrs.PlateCarree())
        ax.plot(samples["lons"], samples["lats"], color="black", linewidth=1.8,
                linestyle="--", transform=ccrs.PlateCarree(), label="JFK-LAX great circle")
        ax.scatter([JFK[1], LAX[1]], [JFK[0], LAX[0]], color="black", s=40, zorder=5,
                   transform=ccrs.PlateCarree())
        ax.annotate("JFK", (JFK[1], JFK[0]), xytext=(6, 4), textcoords="offset points",
                    transform=ccrs.PlateCarree(), fontsize=9, fontweight="bold")
        ax.annotate("LAX", (LAX[1], LAX[0]), xytext=(6, 4), textcoords="offset points",
                    transform=ccrs.PlateCarree(), fontsize=9, fontweight="bold")
    except Exception as e:
        print(f"cartopy plotting failed ({e}); falling back to plain lat/lon pcolormesh")
        fig, ax = plt.subplots(figsize=(10, 6.5))
        mesh = ax.pcolormesh(lons, lats, plot_vals, cmap="turbo", vmin=0, vmax=0.4, shading="auto")
        ax.plot(samples["lons"], samples["lats"], color="black", linewidth=1.8, linestyle="--")
        ax.scatter([JFK[1], LAX[1]], [JFK[0], LAX[0]], color="black", s=40, zorder=5)
        ax.set_xlabel("Longitude")
        ax.set_ylabel("Latitude")

    cbar = fig.colorbar(mesh, ax=ax, orientation="vertical", shrink=0.7, pad=0.02)
    cbar.set_label("EDR (m$^{2/3}$ s$^{-1}$)")
    ax.set_title(f"GTGN EDR nowcast at FL350 -- {path.name}\nJFK -> LAX great-circle route overlaid")
    fig.tight_layout()
    out = OUT_DIR / "fl350_map.png"
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print(f"Wrote {out}")


def plot_profile():
    samples = np.load(gc.DATA_DIR / "route_samples.npz")
    dist_km = samples["dist_km"]
    edr_fl350 = samples["edr_fl350"]
    edr_profile = samples["edr_profile"]
    target_ft = samples["target_ft"]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(10, 7), sharex=True,
                                    gridspec_kw={"height_ratios": [3, 1]})

    ax1.plot(dist_km, edr_fl350, color="tab:blue", linewidth=1.5, label="Constant FL350 cruise")
    ax1.plot(dist_km, edr_profile, color="tab:orange", linewidth=1.5,
              label="Climb/cruise/descent profile", linestyle="--")

    ymax = max(0.25, np.nanmax(edr_fl350) * 1.4)
    ax1.set_ylim(0, ymax)

    colors = {"Light": "#c9b400", "Moderate": "#ff8c00", "Severe": "#d1001c", "Extreme": "#7a0026"}
    off_scale = []
    for name, thresh in MEDIUM_THRESHOLDS.items():
        if thresh <= ymax:
            ax1.axhline(thresh, color=colors[name], linewidth=1, linestyle=":")
            ax1.text(dist_km.max() * 1.01, thresh, name, color=colors[name],
                      va="center", fontsize=8, fontweight="bold", clip_on=False)
        else:
            off_scale.append(f"{name} ({thresh:.2f})")
    if off_scale:
        ax1.text(0.01, 0.02, "Not reached on this route (off-scale): " + ", ".join(off_scale),
                  transform=ax1.transAxes, fontsize=8, color="0.4", va="bottom")

    ax1.set_ylabel("EDR (m$^{2/3}$ s$^{-1}$)")
    ax1.set_title("GTGN EDR along JFK -> LAX route\n"
                   "Threshold lines = medium/large-aircraft categories "
                   "(calibrated against PIREPs, calibrate.py)")
    ax1.legend(loc="upper left", fontsize=9)
    ax1.set_xlim(0, dist_km.max())

    ax2.plot(dist_km, target_ft / 1000.0, color="tab:green", linewidth=1.5)
    ax2.set_ylabel("Level (thousand ft)")
    ax2.set_xlabel("Distance along route (km)")
    ax2.set_title("Climb/cruise/descent altitude profile", fontsize=9)

    fig.tight_layout()
    out = OUT_DIR / "route_profile.png"
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print(f"Wrote {out}")


def main():
    OUT_DIR.mkdir(exist_ok=True)
    path = gc.default_grib_path()
    plot_map(path)
    plot_profile()


if __name__ == "__main__":
    main()
