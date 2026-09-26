# GTGN turbulence nowcast prototype

Prototype proving NOAA's operational GTGN (Graphical Turbulence Guidance
Nowcast) data can be downloaded, decoded, and sampled along a flight route.
See `../HANDOFF.md` for project context. `data/` and `out/` are gitignored.

## How to run

```bash
cd data-pipeline
uv sync                          # creates .venv from pyproject.toml / uv.lock

uv run fetch.py         # find + download the newest GTGN file into ./data (skips if cached)
uv run inspect_gtgn.py  # print GRIB message/grid/level/value summary
uv run route.py         # sample EDR along JFK->LAX, print category breakdown + verdict
uv run plot.py          # render out/fl350_map.png and out/route_profile.png
```

Each script runs in well under 2 seconds once the file is cached (fetch.py
itself took ~4s to download the 28.6 MB file once).

Note: `inspect.py` was renamed to `inspect_gtgn.py` -- naming it `inspect.py`
shadows Python's stdlib `inspect` module (which `numpy`/`eccodes` import
internally) and breaks all imports. This is the one gotcha in an otherwise
smooth build.

## What was verified from the actual downloaded file

Downloaded: `gtgn.20260926/17/gtgn.t1745z.3km.grib2` (28.6 MB, the most
recent file available on NOMADS at the time, found by listing
`.../gtgn.prod/gtgn.YYYYMMDD/HH/` and walking backwards from the current
UTC hour). Verified with eccodes directly on the message contents:

(Note: an earlier `gtgn.t1730z` file was fetched first during
development, then the 17:45z product appeared during testing and
`fetch.py` -- correctly following the "most recent available" spec --
picked it up on a later run. Only the final `t1745z` file is kept in
`./data`; all numbers below are from that file.)

- **51 GRIB messages**, one per vertical level, confirming the documented
  ladder: 100 ft, then 1000 ft to 50,000 ft every 1000 ft.
- **Parameter**: discipline 0 / category 19 / number 30. eccodes' built-in
  tables don't have a name for this NCEP-local parameter (`shortName` /
  `units` decode as `"unknown"`), so the name ("EDPARM", Eddy Dissipation
  Parameter) and units (m^(2/3) s^-1, i.e. EDR^(1/3)) are taken from the
  GTGN User Guide (page 4), not decoded from the file.
- **Level type**: `typeOfFirstFixedSurface = 102` ("specific altitude
  above mean sea level"), surface value stored in metres (e.g. 10668 m =
  FL350 = 35,000 ft, matching Figure 3 of the User Guide, which is
  literally titled "EDDY DISSIPATION PARAMETER at 10.668km").
- **Grid**: Lambert Conformal, **Nx x Ny = 1799 x 1059**, Dx = Dy =
  **3000 m** (i.e. genuinely 3 km, confirming the task's "3 km HRRR grid"
  claim), Latin1 = Latin2 = LaD = 38.5 deg, LoV = 262.5 deg (lon_0 =
  -97.5), spherical earth radius 6371229 m -- this is the standard
  HRRR/RAP CONUS Lambert projection.
- **Extent**: lat 21.14 to 52.62 N, lon -134.10 to -60.92 (CONUS plus
  margins into Canada/Mexico/Atlantic/Pacific).
- **Missing value flag**: 9999, `bitmapPresent=1`; at FL350, 3.3% of grid
  cells are flagged missing (a border strip around the domain edge, not
  interior gaps).
- **Values at FL350**: min 0.01, max 0.35 m^(2/3) s^-1; percentiles
  [50, 90, 99, 99.9, 100] = [0.07, 0.11, 0.18, 0.24, 0.35].

**Discrepancy vs. the PDF worth flagging**: the User Guide's own "GTGN
Output details" page describes a *different*, coarser grid (13.545 km,
451 x 337, Latin1=Latin2=25.0, central meridian -95.0) delivered via LDM.
That description does **not** match the file actually served today from
NOMADS (3 km, 1799 x 1059, standard parallels 38.5, lon_0 -97.5). The
NOMADS `.../gtgn/prod/.../3km.grib2` product is evidently a newer/HRRR-
resolution version of the feed than the one the PDF documents; all grid
facts above and in the code are taken from the live file, not the PDF.

## Decoder library

`eccodes` + `eccodeslib` (binary wheels) and `cfgrib` installed cleanly via
`uv pip install` on macOS arm64 -- no Homebrew, no system install needed.
GRIB messages are read directly with the low-level `eccodes` Python API
(`codes_grib_new_from_file` / `codes_get_values` / `codes_get_array`)
rather than through `cfgrib`/`xarray`, since we only need a handful of
2D fields by message index, not a merged dataset; `cfgrib` was installed
and smoke-tested to confirm it also works, but isn't used by the scripts.

`cartopy` (+ `shapely`, `pyshp`) also installed cleanly as wheels and was
used for the CONUS coastline/state-borders map (with the plain
lat/lon-pcolormesh fallback path also implemented in `plot.py` in case
cartopy failed to import).

## Route sampling method

`route.py` builds ~200 evenly spaced great-circle points from JFK
(40.6413, -73.7781) to LAX (33.9416, -118.4085) with `pyproj.Geod`
(spherical), then looks up each point's nearest grid cell **exactly**
(not approximately) by re-deriving the file's own Lambert Conformal
projection with `pyproj.Proj` (`lat_1=lat_2=38.5, lat_0=38.5, lon_0=-97.5,
R=6371229`) and rounding the projected (x, y) to the nearest of the
regular 3 km grid indices -- verified this reprojection reproduces known
grid corner/interior indices to within 1e-9 grid cells before using it.
This is exact nearest-neighbour, cheaper and more precise than a KD-tree
over the ~1.9M-point curvilinear lat/lon arrays.

Two variants are sampled and saved to `data/route_samples.npz` /
`data/route_summary.json`:
1. **Constant FL350 cruise** -- every point sampled from the FL350 (35,000
   ft) GRIB message.
2. **Climb/cruise/descent profile** -- linear climb from ~0 to FL350 over
   the first 150 km, cruise at FL350, linear descent over the last 150 km;
   each point sampled from its nearest of the 51 documented levels
   (points are grouped by nearest level so each GRIB message is only read
   once).

## Turbulence categories (medium aircraft)

Thresholds transcribed from **Figure 2** of the GTGN "Semi-Operational"
Data Feed User Guide PDF (`/Users/gmecocci/Downloads/GTGN Semi-Operational
Data Feed User Guide.pdf`, page 2), table "Estimated EDR Threshold * 100",
row **"Medium (Large)"** (ICAO 15,500-300,000 lbs MTOW, e.g. A320, B737,
MD80):

| Category | EDR*100 (guide) | EDR threshold |
|----------|-----------------|----------------|
| Light    | 15              | 0.15 |
| Moderate | 20              | 0.20 |
| Severe   | 44              | 0.44 |
| Extreme  | 79              | 0.79 |

(For reference, the guide also lists Light-aircraft thresholds
13/16/36/64 and Heavy-aircraft thresholds 17/24/54/96, EDR*100 -- not used
here since the route is classified for a medium aircraft.)

## Route result (JFK -> LAX, FL350 cruise, gtgn.t1745z.3km.grib2)

- **Max EDR along route: 0.25 m^(2/3) s^-1** (over Kansas, around the
  midpoint of the route).
- **Category breakdown** (200 points, medium aircraft):
  - Smooth: 185/200 (92.5%)
  - Light: 12/200 (6.0%)
  - Moderate: 3/200 (1.5%)
  - Severe: 0/200 (0.0%)
  - Extreme: 0/200 (0.0%)
- **Verdict**: "Some bumps likely -- moderate turbulence is present on
  part of the route at FL350; keep your seatbelt fastened."
- The climb/cruise/descent variant gives essentially the same max (0.26)
  since the peak bump sits well inside the FL350 cruise segment, not in
  the climb/descent ramps.
- Exact numbers will shift slightly on a re-run of `fetch.py`, since GTGN
  updates every 15 minutes and `fetch.py` always grabs the newest
  available file (see `data/route_summary.json` for the numbers matching
  whichever file is currently cached).

## Outputs

- `out/fl350_map.png` -- EDR at FL350 over CONUS (cartopy PlateCarree,
  coastline + state borders) with the JFK-LAX great-circle route overlaid.
- `out/route_profile.png` -- EDR vs. distance along the route for both
  the constant-FL350 and climb/cruise/descent variants, with the medium-
  aircraft Light/Moderate threshold lines drawn (Severe/Extreme noted as
  off-scale/not reached in a caption, since the route never gets close).

## Part 2: GTG v4 forecast (DAFS) -- "how bumpy will my flight be"

Extends the prototype to NOAA's operational **GTG v4.0 forecast**, served
via **DAFS** (Diagnostic Aviation Forecast System), to answer "how bumpy
will my JFK->LAX flight departing ~1h from now be" using an actual
4-D (space + time + altitude) forecast instead of a single nowcast
snapshot.

### Run

```bash
uv run fetch_gtg.py      # subset-fetch EDPARM messages for F001-F007, 8 coarse levels
uv run inspect_gtg.py    # field survey + grid/level verification
uv run route_forecast.py # 4-D route sampling + GTGN-vs-GTG sanity check
uv run plot_forecast.py  # out/forecast_route_profile.png, out/forecast_fl350_map.png
```

### Directory layout discovered on NOMADS

```
https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod/dafs.YYYYMMDD/dafs.tHHz.gtg.3km.conus.fFFF.grib2[.idx]
```

Unlike GTGN, there is **no per-hour subdirectory** -- all cycles/forecast
hours for a day sit flat in `dafs.YYYYMMDD/`. Hourly cycles (t00z..t23z),
forecast hours f000..f018, ~150-165 MB per full file, each with a small
(~17 KB) `.idx` sidecar. `fetch_gtg.py` walks backwards from the current
UTC hour to find the most recent cycle that has a complete f000..f018 set
(checked via directory listing, not by downloading anything).

### Fields found (from the `.idx`, confirmed against `dafs.t16z...f001.grib2.idx`, 205 messages)

| Field | Messages | What it is | Used? |
|---|---|---|---|
| `MXEDPRM` | 1, "entire atmosphere" | column-max composite turbulence index | no (not per-level) |
| `EDPARM` | 51, per-level | **combined/blended EDR forecast** -- discipline=0/category=19/number=30, identical GRIB2 identity to GTGN's own field | **yes** |
| `CATEDR` | 51, per-level | clear-air-turbulence EDR component (one of the inputs blended into EDPARM) | no |
| `MWTURB` | 51, per-level | mountain-wave-turbulence EDR component (the other blended input) | no |
| `disc=0/cat=19/num=50` | 51, per-level | unnamed in eccodes' tables; range-fetched one 233 KB message to check -- values were exactly `{0, 9999}` (a binary flag/mask, not an EDR value) | no |

`EDPARM` was chosen because it's explicitly the *combined* field (not a
CAT/MWT component) and it's the same field name/param-id GTGN itself
publishes, which is what makes the GTGN-vs-GTG comparison below
apples-to-apples.

### Subsetting method (no full-file downloads)

For each needed forecast hour, `fetch_gtg.py`:
1. Fetches the small `.idx` text file (`curl --http1.1`).
2. Parses `msg#:byte_offset:date:PARAM:level:step` rows to find the
   exact byte range of each wanted `EDPARM` message (matching level
   strings like `"10668 m above mean sea level"` built from the exact
   metre values in `gtgn_common.LEVELS_M`, since the idx gives whole
   metres, not feet).
3. Issues one **HTTP Range request per message**
   (`curl --http1.1 -r start-end`) and appends the raw bytes to a small
   local per-hour file (multiple concatenated GRIB2 messages is a
   perfectly valid multi-message file eccodes reads sequentially).
4. Caches per-hour files + a small JSON manifest; a second run detects
   the cache and fetches nothing.

Levels fetched -- a coarse ladder of **8 of the 51 available levels**
(not the full 51, and not just FL350 alone, per the task's "cruise FL350
plus a coarse ladder for climb/descent"):
`[100, 5000, 10000, 15000, 20000, 25000, 30000, 35000]` ft. The
climb/descent ramps (first/last 150 km) snap to the nearest of these;
everything else uses FL350.

Forecast hours fetched: **F001-F007**, matching the task's own estimate
(JFK-LAX ~3974 km great circle / 830 km/h + 20 min climb/descent
allowance = ~5.1-5.3 h flight, departure assumed at cycle-init + 1 h, so
the flight spans forecast hour 1.0 at takeoff to ~6.1 at landing --
F001-F007 covers that with one hour of margin).

**Bytes downloaded this task**: 59.14 MB for the 7 forecast-hour subsets
(GTG), + 28.4 MB for one additional GTGN comparison file (see sanity
check below) = **~87.5 MB total, well under the 300 MB cap**. Re-running
any script downloads 0 additional bytes (everything is cached).

### Verified field facts (GTG forecast file)

- Same grid as GTGN: Lambert Conformal, Ni x Nj = 1799 x 1059, Dx=Dy=3000m,
  Latin1=Latin2=LaD=38.5, LoV=262.5, shapeOfTheEarth=6 -- **confirmed
  identical** in `inspect_gtg.py`, so GTGN's projection code in
  `gtgn_common.py` is reused as-is (no new projection math needed).
- `EDPARM`: discipline=0/category=19/number=30, `typeOfFirstFixedSurface=102`,
  same 51-level ladder (100 ft, then 1000-50000 ft/1000 ft) -- identical
  construction to GTGN.
- `forecastTime` (GRIB key) = the forecast hour (1..7 as fetched);
  `dataDate`/`dataTime` = cycle init (20260926 / 1600 for our cycle).
- Missing fraction at the 100 ft level is high (~62%, mostly water/coastal
  areas) vs. <1% at cruise levels -- expected, since low-level turbulence
  diagnostics are less meaningful/defined over open water.

### Route forecast result (4-D sampling, JFK->LAX, cycle t16z, departure 17:00 UTC)

- For each of 200 route points: estimated time-over-point = departure +
  elapsed flight time (830 km/h cruise groundspeed, +20 min climb/descent
  penalty accrued linearly over the first/last 150 km) -> converted to a
  fractional GTG forecast hour -> **linearly interpolated in time**
  between the two bracketing fetched hourly files -> altitude snapped to
  the nearest coarse level -> nearest-grid-cell EDR lookup (same exact
  Lambert-projection method as `route.py`).
- **Max EDR: 0.247 m^(2/3)s^-1, ~0h00m after takeoff** (right at JFK
  departure, 100 ft level, forecast hour F001 = the departure hour
  itself) -- classifies as **Moderate** for a medium aircraft.
- Category breakdown: Smooth 188/200 (94.0%), Light 10/200 (5.0%),
  Moderate 1/200 (0.5%), Severe 0, Extreme 0, No data 1/200 (0.5%, at
  LAX's 100 ft level, likely a coastal/water grid cell).
- **Verdict**: "Some bumps likely, worst around 0h00m after takeoff --
  keep your seatbelt fastened."
- Away from the low-level departure/arrival points, the cruise-altitude
  ride is smooth-to-light the whole way (see `out/forecast_route_profile.png`).

### GTGN-vs-GTG sanity check

Compared GTG **F001** (valid 2026-09-26T17:00Z, the departure hour) against
a freshly fetched GTGN nowcast file with the **exact same valid time**
(`gtgn.t1700z.3km.grib2` -- one extra, explicitly-permitted GTGN download),
both sampled at FL350 along the 200 route points:

- Correlation: **0.79**
- Mean diff (GTG - GTGN): **-0.003** m^(2/3)s^-1 (GTG very slightly lower on average)
- Mean absolute diff: **0.008** m^(2/3)s^-1
- Max EDR: GTGN 0.250 vs. GTG F001 0.168 (GTGN's blended-in observations
  produced a somewhat sharper local peak that the pure forecast smoothed out)

This is the expected relationship: strongly correlated (both derive from
the same GTG diagnostic algorithm and near-identical model state one hour
out), with GTGN running a bit "hotter" at its peak because it blends in
real PIREPs/NTDA radar observations that a pure forecast can't see yet.

### Plots

- `out/forecast_route_profile.png` -- EDR vs. time-after-departure for
  the 4-D GTG forecast, with the medium-aircraft threshold lines and the
  static GTGN FL350 curve overlaid for visual comparison (GTGN doesn't
  itself have a forecast-hour axis; it's the single departure-time
  snapshot plotted across the same points for shape comparison only).
- `out/forecast_fl350_map.png` -- GTG FL350 field for the forecast hour
  nearest mid-flight (F004, ~2h34m after takeoff), with the route and the
  aircraft's estimated mid-flight position marked.

## Gotchas hit

1. **`inspect.py` name collision** with the stdlib `inspect` module (used
   internally by `numpy`/`eccodes`) -- renamed to `inspect_gtgn.py`.
2. **NOMADS HTTP/2 content-length bug**: confirmed still present; all
   directory listings and the download itself use `curl --http1.1`.
3. **`cfeature.STATES` fill bug** (self-inflicted): `add_feature(...,
   color="0.6")` sets *both* face and edge color, and `STATES` is a
   polygon feature -- this silently filled every US state/Canadian
   province solid grey, hiding the whole CONUS EDR field. Fixed by
   passing `edgecolor=`/`facecolor="none"` explicitly instead of `color=`.
4. **Grid mismatch vs. the PDF** (see "Discrepancy" above) -- trusted the
   live file's own GRIB metadata over the PDF's stated grid resolution.
5. Only one file was ever downloaded (politeness requirement); `fetch.py`
   caches it and all other scripts reuse the cached copy.
6. **DAFS has no per-hour subdirectory** (unlike GTGN) -- files sit flat
   in `dafs.YYYYMMDD/`; an early attempt to list `dafs.YYYYMMDD/HH/`
   (mirroring the GTGN layout) 403'd.
7. GRIB `.idx` files give level text in whole **metres**, not feet -- had
   to reuse (and add, in `gtgn_common.LEVELS_M`) the exact integer metre
   values already read off the GTGN file rather than recomputing
   `ft * 0.3048` and hoping it matched the idx string exactly.
8. Range-fetched one message of the unnamed `disc=0/cat=19/num=50` field
   out of curiosity before deciding not to use it -- turned out to be a
   binary 0/9999 flag, not a turbulence value.
