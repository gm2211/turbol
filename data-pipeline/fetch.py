#!/usr/bin/env python3
"""
Find and download the most recent available NOAA GTGN turbulence nowcast
GRIB2 file from NOMADS, caching it locally under ./data/.

Directory layout on NOMADS:
  https://nomads.ncep.noaa.gov/pub/data/nccf/com/gtgn/prod/gtgn.YYYYMMDD/HH/gtgn.tHHMMz.3km.grib2
MM is one of 00, 15, 30, 45 (product updates every 15 minutes, UTC).

NOMADS sends a malformed HTTP/2 content-length header for these files, so we
force HTTP/1.1 for both directory listings and the download itself (curl
--http1.1). We only ever download ONE file, and skip the download entirely
if a matching file is already cached in ./data/.
"""
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = "https://nomads.ncep.noaa.gov/pub/data/nccf/com/gtgn/prod"
DATA_DIR = Path(__file__).parent / "data"


def list_dir(url: str) -> str:
    """Fetch a NOMADS directory listing over HTTP/1.1 (avoids the broken
    HTTP/2 content-length header this server sends)."""
    result = subprocess.run(
        ["curl", "--http1.1", "-s", "--fail", url],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode != 0:
        return ""
    return result.stdout


def find_latest_file():
    """Walk backwards from the current UTC hour looking for the most
    recent gtgn.tHHMMz.3km.grib2 file, falling back across hours and
    into the previous day if needed."""
    now = datetime.now(timezone.utc)
    # Search today and yesterday (UTC), most recent hour first.
    for day_offset in (0, 1):
        day = now - timedelta(days=day_offset)
        day_str = day.strftime("%Y%m%d")
        day_url = f"{BASE}/gtgn.{day_str}/"
        listing = list_dir(day_url)
        if not listing:
            continue
        hours = sorted(set(re.findall(r'href="(\d{2})/"', listing)), reverse=True)
        if day_offset == 0:
            # don't bother with hours in the future relative to "now"
            hours = [h for h in hours if int(h) <= now.hour]
        for hh in hours:
            hour_url = f"{day_url}{hh}/"
            hour_listing = list_dir(hour_url)
            files = sorted(set(re.findall(
                rf'href="(gtgn\.t{hh}[0-9]{{2}}z\.3km\.grib2)"', hour_listing
            )), reverse=True)
            if files:
                fname = files[0]
                return day_str, hh, fname, f"{hour_url}{fname}"
    return None


def download(url: str, dest: Path):
    if dest.exists() and dest.stat().st_size > 0:
        print(f"Cached already: {dest} ({dest.stat().st_size / 1e6:.1f} MB) — skipping download.")
        return
    DATA_DIR.mkdir(exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    print(f"Downloading {url} -> {dest}")
    result = subprocess.run(
        ["curl", "--http1.1", "-s", "--fail", "-o", str(tmp), url],
        timeout=180,
    )
    if result.returncode != 0 or not tmp.exists():
        print("Download failed.", file=sys.stderr)
        sys.exit(1)
    tmp.rename(dest)
    print(f"Saved {dest} ({dest.stat().st_size / 1e6:.1f} MB)")


def main():
    found = find_latest_file()
    if not found:
        print("Could not find any GTGN file in the last two UTC days.", file=sys.stderr)
        sys.exit(1)
    day_str, hh, fname, url = found
    print(f"Most recent available: gtgn.{day_str}/{hh}/{fname}")
    dest = DATA_DIR / fname
    download(url, dest)
    # Write a small pointer file so other scripts know which file to use.
    (DATA_DIR / "latest.txt").write_text(fname + "\n")
    print(f"latest -> {fname}")


if __name__ == "__main__":
    main()
