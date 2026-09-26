"""
Shared helpers for reading the GTGN GRIB2 file with eccodes and for
projecting lat/lon onto its Lambert Conformal grid.

Grid facts (verified directly from data/gtgn.t1730z.3km.grib2 with
eccodes; see inspect.py output / README.md for the full report):
  - GRIB2 discipline 0, category 19, parameter 30 ("EDPARM" / Eddy
    Dissipation Parameter per the GTGN User Guide, EDR^(1/3) units
    m^(2/3) s^-1). eccodes' default tables don't have a local name for
    this NCEP parameter, so shortName/units come back "unknown" -- the
    name/units are taken from the User Guide (page 4), not decoded.
  - 51 messages, one per vertical level, in file order: 100 ft, then
    1000 ft to 50000 ft in 1000 ft steps (typeOfFirstFixedSurface=102,
    "specific altitude above mean sea level"; the surface value is
    given in metres in the GRIB message).
  - Grid: Lambert Conformal, Nx=1799, Ny=1059, Dx=Dy=3000 m,
    Latin1=Latin2=LaD=38.5 deg, LoV=262.5 deg (i.e. lon_0=-97.5),
    spherical earth radius 6371229 m (shapeOfTheEarth=6) -- this is
    the standard HRRR/RAP CONUS Lambert projection.
  - First grid point: lat 21.138124 N, lon 237.28048 E (= -122.71952).
  - Missing value flag: 9999 (bitmap present).

Note: this is the CURRENT 3-km HRRR-based product. The GTGN User Guide
PDF (Figure on its 3rd page) documents an older 13.545-km, 451x337 LDM
feed with Latin1=Latin2=25, central meridian -95 -- that grid does NOT
match this file. We verified everything below from the actual
downloaded file, not from the PDF, and note the discrepancy in
README.md.
"""
from pathlib import Path

import eccodes
import numpy as np
from pyproj import Proj

DATA_DIR = Path(__file__).parent / "data"
MISSING = 9999

# Level ladder as documented in the User Guide and confirmed from the
# file's scaledValueOfFirstFixedSurface (metres): message 1 = 100 ft,
# messages 2..51 = 1000..50000 ft in 1000 ft steps.
LEVELS_FT = [100] + list(range(1000, 50001, 1000))
assert len(LEVELS_FT) == 51

# Exact integer metre values GRIB stores for each of the 51 levels above
# (scaledValueOfFirstFixedSurface), read directly from the GTGN file and
# confirmed identical in the DAFS/GTG forecast file (same HRRR 3km grid
# and level construction). Used to build exact .idx level-string matches
# ("<N> m above mean sea level") when Range-fetching GTG messages, since
# the idx text gives whole metres, not feet.
LEVELS_M = [
    30, 304, 609, 914, 1219, 1524, 1828, 2133, 2438, 2743, 3048, 3352,
    3657, 3962, 4267, 4572, 4876, 5181, 5486, 5791, 6096, 6400, 6705,
    7010, 7315, 7620, 7924, 8229, 8534, 8839, 9144, 9448, 9753, 10058,
    10363, 10668, 10972, 11277, 11582, 11887, 12192, 12496, 12801, 13106,
    13411, 13716, 14020, 14325, 14630, 14935, 15240,
]
assert len(LEVELS_M) == 51


def level_m_for_ft(level_ft: int) -> int:
    """Exact GRIB metre value for a documented level in feet."""
    return LEVELS_M[LEVELS_FT.index(level_ft)]

# HRRR/RAP CONUS Lambert Conformal projection, as read from the file.
LCC_PARAMS = dict(proj="lcc", lat_1=38.5, lat_2=38.5, lat_0=38.5,
                   lon_0=-97.5, R=6371229, x_0=0, y_0=0)
DX = DY = 3000.0


def default_grib_path() -> Path:
    latest = DATA_DIR / "latest.txt"
    if not latest.exists():
        raise FileNotFoundError("No cached file -- run fetch.py first.")
    return DATA_DIR / latest.read_text().strip()


def message_index_for_level_ft(level_ft: int) -> int:
    """1-based GRIB message index for a given altitude level in feet."""
    return LEVELS_FT.index(level_ft) + 1


def read_message(path: Path, msg_index_1based: int):
    """Return (values 2D [Nj,Ni], lats 2D, lons2D[-180,180], level_ft,
    missing_value) for the given 1-based message index."""
    with open(path, "rb") as f:
        gid = None
        for _ in range(msg_index_1based):
            if gid is not None:
                eccodes.codes_release(gid)
            gid = eccodes.codes_grib_new_from_file(f)
            if gid is None:
                raise IndexError(f"file has fewer than {msg_index_1based} messages")
        ni = eccodes.codes_get(gid, "Ni")
        nj = eccodes.codes_get(gid, "Nj")
        vals = eccodes.codes_get_values(gid).reshape(nj, ni)
        lats = eccodes.codes_get_array(gid, "latitudes").reshape(nj, ni)
        lons = eccodes.codes_get_array(gid, "longitudes").reshape(nj, ni)
        missing = eccodes.codes_get(gid, "missingValue")
        level_m = eccodes.codes_get(gid, "scaledValueOfFirstFixedSurface")
        eccodes.codes_release(gid)
    lons = np.where(lons > 180, lons - 360, lons)
    return vals, lats, lons, level_m, missing


def grid_projector():
    """Return a pyproj Proj for the file's Lambert Conformal grid, plus
    the (x0, y0) projected coordinate of grid point (i=0, j=0)."""
    p = Proj(**LCC_PARAMS)
    lon0, lat0 = 237.28048 - 360, 21.138124
    x0, y0 = p(lon0, lat0)
    return p, x0, y0


def read_all_messages(path: Path):
    """Read every GRIB message in a (small, subset) file. Returns a list
    of dicts with vals/lats/lons/level_m/missing/forecast_hour/valid
    metadata, in file order. Used for the small per-forecast-hour GTG
    subset files fetch_gtg.py produces (a handful of messages each, not
    the full 150+ MB originals)."""
    out = []
    with open(path, "rb") as f:
        while True:
            gid = eccodes.codes_grib_new_from_file(f)
            if gid is None:
                break
            ni = eccodes.codes_get(gid, "Ni")
            nj = eccodes.codes_get(gid, "Nj")
            vals = eccodes.codes_get_values(gid).reshape(nj, ni)
            lats = eccodes.codes_get_array(gid, "latitudes").reshape(nj, ni)
            lons = eccodes.codes_get_array(gid, "longitudes").reshape(nj, ni)
            missing = eccodes.codes_get(gid, "missingValue")
            level_m = eccodes.codes_get(gid, "scaledValueOfFirstFixedSurface")
            fhour = eccodes.codes_get(gid, "forecastTime")
            data_date = eccodes.codes_get(gid, "dataDate")
            data_time = eccodes.codes_get(gid, "dataTime")
            eccodes.codes_release(gid)
            lons = np.where(lons > 180, lons - 360, lons)
            out.append(dict(vals=vals, lats=lats, lons=lons, level_m=level_m,
                             missing=missing, forecast_hour=fhour,
                             data_date=data_date, data_time=data_time))
    return out


def latlon_to_ij(lats, lons, p=None, x0=None, y0=None):
    """Vectorised projection of arrays of lat/lon (deg) to nearest
    (i, j) integer grid indices via the exact Lambert Conformal
    projection (grid is regular in projected x/y space, so this is
    exact nearest-neighbour, not an approximation)."""
    if p is None:
        p, x0, y0 = grid_projector()
    x, y = p(lons, lats)
    i = np.rint((x - x0) / DX).astype(int)
    j = np.rint((y - y0) / DY).astype(int)
    return i, j
