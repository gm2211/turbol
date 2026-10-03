#!/usr/bin/env python3
"""Render out/forecast_route_profile.png (EDR vs time-after-departure,
with medium-aircraft thresholds and a GTGN FL350 overlay for the
comparable window) and out/forecast_fl350_map.png (GTG forecast hour at
mid-flight, with the route and the aircraft's estimated position)."""
import json

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

import gtgn_common as gc
from fetch_gtg import GTG_DIR
from route import JFK, LAX, MEDIUM_THRESHOLDS

OUT_DIR = gc.DATA_DIR.parent / "out"


def plot_profile():
    samples = np.load(gc.DATA_DIR / "route_forecast_samples.npz")
    elapsed_h = samples["elapsed_h"]
    edr = samples["edr"]
    curves = np.load(gc.DATA_DIR / "gtgn_vs_gtg_fl350_curves.npz")
    gtgn_fl350 = curves["gtgn_fl350"]

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(10, 7), sharex=True,
                                    gridspec_kw={"height_ratios": [3, 1]})

    ax1.plot(elapsed_h, edr, color="tab:blue", linewidth=1.5,
              label="Forecast used (GTG 4-D, blended with GTGN nowcast in the first hour)")
    blend = samples["gtgn_weight"] > 0
    ax1.plot(elapsed_h[blend], samples["gtg_edr"][blend], color="tab:blue", linewidth=1,
              linestyle="--", alpha=0.6, label="GTG forecast alone (first hour)")
    near_ground = samples["near_ground"]
    for idx in np.where(near_ground)[0]:
        ax1.axvspan(max(elapsed_h[idx] - 0.02, 0), elapsed_h[idx] + 0.02, color="0.85", zorder=0)
    if near_ground.any():
        ax1.plot([], [], color="0.85", linewidth=6, label="Near ground (not categorised)")
    ax1.plot(elapsed_h, gtgn_fl350, color="tab:purple", linewidth=1.2, linestyle=":",
              label="GTGN nowcast, FL350, static snapshot at departure (comparison only)")

    ymax = max(0.3, np.nanmax(edr) * 1.4)
    ax1.set_ylim(0, ymax)
    colors = {"Light": "#c9b400", "Moderate": "#ff8c00", "Severe": "#d1001c", "Extreme": "#7a0026"}
    off_scale = []
    for name, thresh in MEDIUM_THRESHOLDS.items():
        if thresh <= ymax:
            ax1.axhline(thresh, color=colors[name], linewidth=1, linestyle=":")
            ax1.text(elapsed_h.max() * 1.01, thresh, name, color=colors[name],
                      va="center", fontsize=8, fontweight="bold", clip_on=False)
        else:
            off_scale.append(f"{name} ({thresh:.2f})")
    if off_scale:
        ax1.text(0.01, 0.02, "Not reached on this route (off-scale): " + ", ".join(off_scale),
                  transform=ax1.transAxes, fontsize=8, color="0.4", va="bottom")

    ax1.set_ylabel("EDR (m$^{2/3}$ s$^{-1}$)")
    ax1.set_title("GTG v4 forecast EDR along JFK -> LAX, by time after departure\n"
                   "Threshold lines = medium/large-aircraft categories (calibrated against PIREPs)")
    ax1.legend(loc="upper right", fontsize=8)
    ax1.set_xlim(0, elapsed_h.max())

    ax2.plot(elapsed_h, samples["target_ft"] / 1000.0, color="tab:green", linewidth=1.5)
    ax2.set_ylabel("Level (thousand ft)")
    ax2.set_xlabel("Time after departure (hours)")
    ax2.set_title("Climb/cruise/descent altitude profile (snapped to fetched coarse levels)", fontsize=9)

    fig.tight_layout()
    out = OUT_DIR / "forecast_route_profile.png"
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print(f"Wrote {out}")


def plot_map():
    manifest = json.loads((GTG_DIR / "manifest.json").read_text())
    samples = np.load(gc.DATA_DIR / "route_forecast_samples.npz")
    elapsed_h = samples["elapsed_h"]
    total_elapsed = elapsed_h.max()
    mid_elapsed = total_elapsed / 2.0
    mid_idx = int(np.argmin(np.abs(elapsed_h - mid_elapsed)))
    mid_fhour = int(round(samples["fcst_hour_float"][mid_idx]))
    mid_fhour = max(min(mid_fhour, max(manifest["forecast_hours"])), min(manifest["forecast_hours"]))

    msgs = gc.read_all_messages(GTG_DIR / f"f{mid_fhour:03d}.grib2")
    fl350 = next(m for m in msgs if m["level_m"] == gc.level_m_for_ft(35000))
    plot_vals = np.where(fl350["vals"] == fl350["missing"], np.nan, fl350["vals"])

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
        mesh = ax.pcolormesh(fl350["lons"], fl350["lats"], plot_vals, cmap="turbo", vmin=0, vmax=0.4,
                              shading="auto", transform=ccrs.PlateCarree())
        ax.plot(samples["lons"], samples["lats"], color="black", linewidth=1.8,
                linestyle="--", transform=ccrs.PlateCarree(), label="JFK-LAX route")
        ax.scatter([JFK[1], LAX[1]], [JFK[0], LAX[0]], color="black", s=40, zorder=5,
                   transform=ccrs.PlateCarree())
        ax.annotate("JFK", (JFK[1], JFK[0]), xytext=(6, 4), textcoords="offset points",
                    transform=ccrs.PlateCarree(), fontsize=9, fontweight="bold")
        ax.annotate("LAX", (LAX[1], LAX[0]), xytext=(6, 4), textcoords="offset points",
                    transform=ccrs.PlateCarree(), fontsize=9, fontweight="bold")
        ax.scatter([samples["lons"][mid_idx]], [samples["lats"][mid_idx]], color="red", s=90,
                   marker="^", zorder=6, edgecolor="black", linewidth=0.8,
                   transform=ccrs.PlateCarree(), label="Aircraft (mid-flight estimate)")
    except Exception as e:
        print(f"cartopy plotting failed ({e}); falling back to plain lat/lon pcolormesh")
        fig, ax = plt.subplots(figsize=(10, 6.5))
        mesh = ax.pcolormesh(fl350["lons"], fl350["lats"], plot_vals, cmap="turbo", vmin=0, vmax=0.4,
                              shading="auto")
        ax.plot(samples["lons"], samples["lats"], color="black", linewidth=1.8, linestyle="--")
        ax.scatter([JFK[1], LAX[1]], [JFK[0], LAX[0]], color="black", s=40, zorder=5)
        ax.scatter([samples["lons"][mid_idx]], [samples["lats"][mid_idx]], color="red", s=90,
                   marker="^", zorder=6)
        ax.set_xlabel("Longitude")
        ax.set_ylabel("Latitude")

    cbar = fig.colorbar(mesh, ax=ax, orientation="vertical", shrink=0.7, pad=0.02)
    cbar.set_label("EDR (m$^{2/3}$ s$^{-1}$)")
    hh_m, mm_m = divmod(int(round(mid_elapsed * 60)), 60)
    ax.legend(loc="lower left", fontsize=8)
    ax.set_title(f"GTG v4 forecast EDR at FL350, forecast hour F{mid_fhour:03d}\n"
                 f"(mid-flight, ~{hh_m}h{mm_m:02d}m after takeoff) with JFK-LAX route + aircraft position")
    fig.tight_layout()
    out = OUT_DIR / "forecast_fl350_map.png"
    fig.savefig(out, dpi=130)
    plt.close(fig)
    print(f"Wrote {out}")


def main():
    OUT_DIR.mkdir(exist_ok=True)
    plot_profile()
    plot_map()


if __name__ == "__main__":
    main()
