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
