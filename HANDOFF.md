# Turbol revival — handoff (2026-09-26)

Turbol is a Turbli-like "how bumpy will my flight be" app: Scala backend
(`backend/`), Vue + Leaflet frontend (`frontend/`), DigitalOcean Terraform
(`deployment/`). It stalled in 2023 because real-time GTGN data required a
UCAR LDM feed with a static, allowlisted IP. That blocker no longer exists.

## What changed since 2023

- **GTGN is operational at NOAA** since 2026-07-28 (NWS SCN 26-62). Free on
  NOMADS over HTTPS: no license, no LDM, no static IP.
  `https://nomads.ncep.noaa.gov/pub/data/nccf/com/gtgn/prod/gtgn.YYYYMMDD/HH/gtgn.tHHMMz.3km.grib2`
  Every 15 min, ~27 MB, 3 km HRRR grid, CONUS, nowcast only (no forecast).
- **GTG v4 forecast** replaced GTG v3 on 2025-12-18 (SCN 25-80, "DAFS").
  Hourly cycles, F000–F018, 3 km, CONUS:
  `https://nomads.ncep.noaa.gov/pub/data/nccf/com/dafs/prod/dafs.YYYYMMDD/`
  ~155 MB per forecast hour, so fetch only needed GRIB messages (`.idx`
  byte ranges or the NOMADS grib filter). The old `tgftp` GTG path is gone.
- The UCAR LDM feed and the `GTGN Semi-Operational Data Feed User Guide` are
  obsolete. Its grid description (451×337 @ 13.5 km) does not match the NOAA
  files. Its Figure 2 EDR thresholds are still what the prototype uses.
- Hosting: no static IP needed anymore. (For the record: Render's dedicated
  outbound IPs cost $100/mo on top of Pro; the default Render and Railway IPs
  are shared between customers.)

## Data options (ranked)

1. GTG v4 forecast (NOMADS `dafs`): primary source for future flights, 0–18 h.
2. GTGN (NOMADS `gtgn`): "right now" view, and flights already airborne.
3. Pilot reports (PIREPs) from the `aviationweather.gov/data/api` pirep
   endpoint, for a "recently reported near your route" layer. The API has no
   gridded turbulence.
4. International routes: compute Ellrod-style indices from open GFS data
   (AWS `noaa-gfs-bdp-pds`). WAFS global turbulence is limited to FAA-approved
   users, and IATA Turbulence Aware is commercial. Turbli's sources page cites
   NOAA GFS and GTG.
5. GribStream (third-party): point-query API over GTG with a free tier; limits
   not checked.

## Prototype: `data-pipeline/`

Python, run with `uv`. See `data-pipeline/README.md`. Status: GTGN fetch,
decode, and JFK→LAX route sampling at FL350 all work (verified 2026-09-26).

Verified file facts: HRRR Lambert grid 1799×1059 @ 3 km; 51 levels (100 ft,
then 1,000–50,000 ft) stored in metres MSL; GRIB2 0/19/30 = EDR (eccodes
decodes the name as "unknown"); missing value = 9999.
EDR thresholds, medium aircraft (guide Fig. 2): light 0.15, moderate 0.20,
severe 0.44, extreme 0.79. Light and heavy aircraft thresholds are in
`data-pipeline/README.md`.

Gotchas: download NOMADS with `curl --http1.1`, because its HTTP/2 responses
have a malformed content-length header. Don't name a script `inspect.py`.

## In flight when this was written

An agent was extending the prototype to use the GTG v4 forecast (4-D route
sampling by estimated time over each point, plus a GTG-vs-GTGN check). If
`data-pipeline/` has no `fetch_gtg.py` / `route_forecast.py`, that work did not
land; redo it from the spec below.

## Next steps

1. GTG forecast in the prototype: fetch only needed messages for F001–F007;
   per route point, compute the estimated time over it and sample the
   matching forecast hour and level; report the worst bump and when it
   happens.
2. Recalibrate the categories: the 2023 thresholds were written for the old
   13 km grid, and the 3 km data may show sharper peaks.
3. Port into the Scala backend: `WeatherDataUpdater` pulls from NOMADS
   (NetCDF-Java reads GRIB2), and `FlightsEndpoint` returns a bumpiness
   verdict per route.
4. Simplify deployment: the Kubernetes setup in `deployment/digital-ocean` is
   overkill; one small droplet with Docker Compose is enough.
